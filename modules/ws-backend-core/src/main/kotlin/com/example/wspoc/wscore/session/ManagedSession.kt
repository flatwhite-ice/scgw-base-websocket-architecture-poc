package com.example.wspoc.wscore.session

import com.example.wspoc.auth.token.AuthPrincipal
import com.example.wspoc.wscore.auth.WsPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/**
 * 연결 하나의 수명. [run]이 연결이 끝날 때까지 돌면서 다음을 맡는다.
 *  - 송신 큐: 세션별 bounded `Channel`. 응답은 기다리고([send]) push는 가득 차면 버린다([trySend])
 *  - heartbeat: 연속 `ws.heartbeat.max-missed`회 pong이 없으면 3008
 *  - 토큰 만료: principal의 `expiresAt`에 3000(익명 세션 제외)
 *  - drain: [SessionRegistry]에 등록해 [DrainCoordinator]가 1001로 닫을 수 있게 한다
 *  - 프로토콜 예외: 콜백이나 [scope]의 코루틴이 예외를 던지면 이 세션만 1011로 닫는다
 *  - 메트릭(D27)과 세션 로그(D21)
 *
 * Reactor와의 변환은 여기서만 한다(receive → Flow, Channel → send Flux, close Mono).
 */
internal class ManagedSession(
    private val session: WebSocketSession,
    wsPrincipal: WsPrincipal,
    private val protocol: WsSessionProtocol,
    private val runtime: WsSessionRuntime,
) : WsSessionContext {
    override val id: String get() = session.id
    override val principal: AuthPrincipal = wsPrincipal.auth
    override val anonymous: Boolean = wsPrincipal.anonymous
    override val serverId: String get() = runtime.serverIdentity.id
    override lateinit var scope: CoroutineScope

    private val props = runtime.props
    private val meters = runtime.meters.scope(wsPrincipal.endpoint)
    private val outbound = Channel<WebSocketMessage>(props.outboundBufferSize) { DataBufferUtils.release(it.payload) }
    private val openedAt = System.nanoTime()
    private val closing = AtomicBoolean()

    @Volatile
    private var pongSeen = true

    /** 서버가 보낸 close 상태. null이면 상대(클라이언트, relay에서는 게이트웨이)가 닫은 것이다. */
    @Volatile
    private var serverCloseStatus: CloseStatus? = null

    suspend fun run() =
        coroutineScope {
            scope = protocolScope()
            runtime.registry.register(session.id) { status -> launch { close(status) } }
            meters.sessionOpened()
            SessionLog.opened(session, principal.userId, anonymous, runtime.registry.activeCount)
            try {
                launch { writeOutbound() }
                launch { heartbeat() }
                // 익명 세션에는 만료가 없다(principal.expiresAt은 자리표시자 Instant.MAX).
                if (!anonymous) launch { closeOnExpiry() }
                // 핸드셰이크 필터의 503 검사를 통과한 뒤 drain이 시작됐으면 DrainCoordinator의 snapshot에 없을 수 있다.
                // 등록 뒤에 다시 확인해서 직접 닫는다(snapshot에도 있으면 close가 한 번만 실행된다).
                if (runtime.drainState.draining) launch { close(CloseCodes.GOING_AWAY) }
                guarded { protocol.onOpen(this@ManagedSession) }
                readInbound()
                // 수신이 끝났다 = 연결이 끝났다(상대 또는 우리가 닫음).
            } finally {
                runtime.registry.unregister(session.id)
                recordEnd(runtime.registry.activeCount)
                protocol.onClose(this@ManagedSession)
                outbound.close()
                coroutineContext.cancelChildren()
            }
        }

    // ---------- 송신 ----------

    override suspend fun send(text: String) {
        if (closing.get()) return
        try {
            outbound.send(session.textMessage(text))
        } catch (_: ClosedSendChannelException) {
            // 세션이 닫히는 중이다. 늦은 응답은 버린다(버퍼는 onUndeliveredElement가 해제).
        }
    }

    override fun trySend(text: String): WsSessionContext.Offer {
        val result = outbound.trySend(session.textMessage(text))
        if (result.isSuccess) return WsSessionContext.Offer.SENT
        if (result.isClosed) return WsSessionContext.Offer.CLOSED
        // 큐가 가득 참: 느린 소비자
        meters.messagesDropped.increment()
        return WsSessionContext.Offer.DROPPED
    }

    /** 송신 큐를 소켓에 쓴다. 실제로 소켓에 넘긴 TEXT 프레임만 센다(ping과 큐에서 버려진 것은 세지 않는다). */
    private suspend fun writeOutbound() {
        val frames = outbound.consumeAsFlow().onEach { if (it.type == WebSocketMessage.Type.TEXT) meters.messagesOut.increment() }
        session.send(frames.asFlux()).awaitSingleOrNull()
    }

    // ---------- 수신 ----------

    private suspend fun readInbound() =
        session
            .receive()
            // 여기서 동기적으로 본문을 꺼낸다: Reactor Netty는 onNext 직후 프레임 버퍼를 해제한다.
            .handle<String> { message, sink -> textOf(message)?.let(sink::next) }
            .asFlow()
            .collect { text -> guarded { protocol.onText(this, text) } }

    /** TEXT면 본문을 돌려준다. PONG은 heartbeat에 알리고, 그 밖의 프레임은 프로토콜에 넘기지 않는다. */
    private fun textOf(message: WebSocketMessage): String? =
        when (message.type) {
            WebSocketMessage.Type.TEXT -> {
                meters.messagesIn.increment()
                message.payloadAsText
            }

            // 프로토콜은 BINARY를 처리하지 않는다. 그래도 받은 데이터 프레임이므로 센다(게이트웨이와 같은 기준, D27).
            WebSocketMessage.Type.BINARY -> {
                meters.messagesIn.increment()
                null
            }

            WebSocketMessage.Type.PONG -> {
                pongSeen = true
                null
            }

            else -> {
                null
            }
        }

    // ---------- 수명 ----------

    private suspend fun heartbeat() {
        val interval = props.heartbeat.interval.toMillis()
        var missed = 0
        while (true) {
            delay(interval)
            if (pongSeen) {
                missed = 0
            } else if (++missed >= props.heartbeat.maxMissed) {
                return close(CloseCodes.HEARTBEAT_TIMEOUT)
            }
            pongSeen = false
            outbound.trySend(session.pingMessage { it.wrap(ByteArray(0)) })
        }
    }

    private suspend fun closeOnExpiry() {
        delay(Duration.between(runtime.clock.instant(), principal.expiresAt).toMillis().coerceAtLeast(0))
        close(CloseCodes.TOKEN_EXPIRED)
    }

    /**
     * close 프레임을 보낸다. 취소되면 안 된다: Reactor Netty의 `sendClose()`는 inbound를 즉시 끝내고, 그 때문에
     * [run]이 끝나며 자식 코루틴(이 코루틴 포함)을 취소한다. 쓰는 도중 close Mono가 취소되면 Reactor Netty가
     * 프레임을 전송 전에 해제해서, 클라이언트는 보낸 코드 대신 1006을 받는다(D9).
     */
    private suspend fun close(status: CloseStatus) {
        if (!closing.compareAndSet(false, true)) return
        serverCloseStatus = status
        withContext(NonCancellable) { session.close(status).awaitSingleOrNull() }
    }

    // ---------- 프로토콜 예외 ----------

    /**
     * 프로토콜이 띄우는 코루틴의 scope. supervisor 아래에 두어, 하나가 실패해도 세션 코루틴이 통째로 취소되지 않고
     * (그러면 close 프레임 없이 끊긴다) [fail]이 1011로 닫게 한다. 세션이 끝나면 함께 취소된다.
     */
    private fun CoroutineScope.protocolScope(): CoroutineScope {
        val onFailure = CoroutineExceptionHandler { _, cause -> launch { fail(cause) } }
        return CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job) + onFailure)
    }

    /** 프로토콜 콜백의 예외를 세션 밖으로 내보내지 않는다. 그대로 두면 클라이언트는 코드 없는 종료(1005)만 본다. */
    private suspend inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private suspend fun fail(cause: Throwable) {
        log.warn(cause) { "ws session ${session.id} failed in protocol code, closing with ${CloseCodes.SERVER_ERROR.code}" }
        close(CloseCodes.SERVER_ERROR)
    }

    // ---------- 종료 기록 ----------

    /**
     * 종료 메트릭과 로그. 서버가 닫았으면 그 상태를 바로 쓴다. 상대가 닫았으면 받은 close 프레임의 상태를
     * 기다리지 않고 구독해서 쓰고, 프레임 없이 끊겼으면 1005(No Status)로 남긴다.
     */
    private fun recordEnd(active: Int) {
        val duration = Duration.ofNanos(System.nanoTime() - openedAt)
        meters.sessionEnded(duration.toNanos())
        val byServer = serverCloseStatus
        if (byServer != null) return recordClose(byServer, "server", duration, active)
        session
            .closeStatus()
            .timeout(CLOSE_STATUS_WAIT)
            .onErrorResume { Mono.empty() }
            .defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
            .subscribe { recordClose(it, "client", duration, active) }
    }

    private fun recordClose(
        status: CloseStatus,
        closedBy: String,
        duration: Duration,
        active: Int,
    ) {
        meters.closed(status.code)
        SessionLog.closed(session, principal.userId, status, closedBy, duration.toMillis(), active)
    }

    private companion object {
        /** 상대가 닫은 경우 close 프레임 상태를 기다리는 최대 시간(메트릭과 로그용). */
        val CLOSE_STATUS_WAIT: Duration = Duration.ofSeconds(1)
    }
}
