package com.example.wspoc.wscore.session

import com.example.wspoc.auth.token.AuthPrincipal
import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.wscore.WsProperties
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
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/** 모든 [ManagedWsHandler]가 공유하는 런타임 의존성. 서비스는 이 빈 하나만 주입받으면 된다. */
class WsSessionRuntime(
    val props: WsProperties,
    val registry: SessionRegistry,
    val sessionScope: SessionScope,
    val serverIdentity: ServerIdentity,
    val drainState: DrainState,
    val meters: WsMeters,
    val clock: Clock = Clock.systemUTC(),
)

/**
 * 서비스가 정의하는 메시지 처리([WsSessionProtocol])를 세션 수명 관리와 결합하는 WebSocketHandler.
 * 엔드포인트마다 하나를 `@WsEndpoint` 빈으로 등록하고, 세션마다 [protocolFactory]로 프로토콜을 새로 만든다.
 *
 * 공통으로 처리하는 것:
 *  - [SessionRegistry] 등록(drain 대상), 세션별 송신 큐(bounded `Channel`)
 *  - heartbeat: 연속 `ws.heartbeat.max-missed`회 pong이 없으면 3008
 *  - 토큰 만료: principal의 `expiresAt`에 3000(익명 엔드포인트 제외)
 *  - drain: 등록된 세션은 [DrainCoordinator]가 1001로 닫는다. 핸드셰이크 검사 뒤에 drain이 시작돼 그 목록에 못 든 세션은 스스로 닫는다
 *  - 프로토콜 예외: 콜백이나 세션 scope의 코루틴이 예외를 던지면 그 세션만 1011로 닫는다
 *  - close는 취소되지 않게 보낸다(D9)
 *  - 메트릭(D27): 엔드포인트별 활성 세션, 메시지 수, 버린 메시지 수, close 코드, 세션 수명
 *
 * 각 세션은 [SessionScope.dispatcher]에서 코루틴으로 실행된다. Reactor와의 변환은 이 경계에서만 한다
 * (receive -> Flow, Channel -> send Flux, close Mono).
 */
class ManagedWsHandler(
    private val runtime: WsSessionRuntime,
    private val protocolFactory: () -> WsSessionProtocol,
) : WebSocketHandler {
    private val props = runtime.props

    override fun handle(session: WebSocketSession): Mono<Void> =
        mono(runtime.sessionScope.dispatcher) {
            val principal = session.handshakeInfo.principal.awaitSingleOrNull() as? WsPrincipal
            if (principal == null) {
                // 실제로는 도달하지 않는다: WsHandshakeAuthFilter가 미인증 핸드셰이크를 업그레이드 전에 거절한다.
                session.close(CloseCodes.POLICY_VIOLATION).awaitSingleOrNull()
            } else {
                SessionRun(session, principal.auth, principal.anonymous, runtime.meters.scope(principal.endpoint)).run()
            }
        }.then()

    private sealed interface Inbound {
        data class Text(
            val text: String,
        ) : Inbound

        data object Pong : Inbound

        data object Other : Inbound
    }

    private inner class SessionRun(
        private val session: WebSocketSession,
        override val principal: AuthPrincipal,
        override val anonymous: Boolean,
        private val meters: WsMeters.Scope,
    ) : WsSessionContext {
        private val outbound =
            Channel<WebSocketMessage>(props.outboundBufferSize) { DataBufferUtils.release(it.payload) }
        private val closing = AtomicBoolean()
        private val protocol = protocolFactory()

        @Volatile
        private var pongSeen = true

        /** 서버가 보낸 close 상태. null이면 상대(클라이언트, relay에서는 게이트웨이)가 닫은 것이다. */
        @Volatile
        private var serverCloseStatus: CloseStatus? = null
        private val openedAt = System.nanoTime()

        override val id: String get() = session.id
        override val serverId: String get() = runtime.serverIdentity.id
        override lateinit var scope: CoroutineScope

        suspend fun run() =
            coroutineScope {
                // 프로토콜이 띄우는 코루틴은 supervisor 아래에 둔다. 하나가 실패해도 세션 코루틴이 통째로 취소되어
                // close 프레임 없이 끊기지 않고, 핸들러가 1011로 닫는다. 세션이 끝나면 cancelChildren이 함께 취소한다.
                scope =
                    CoroutineScope(
                        coroutineContext + SupervisorJob(coroutineContext.job) + CoroutineExceptionHandler { _, e -> launch { fail(e) } },
                    )
                runtime.registry.register(session.id) { status -> launch { close(status) } }
                meters.sessionOpened()
                SessionLog.opened(session, principal.userId, anonymous, runtime.registry.activeCount)
                try {
                    launch { session.send(outbound.consumeAsFlow().onEach(::countOutbound).asFlux()).awaitSingleOrNull() }
                    launch { heartbeat() }
                    // 익명 세션에는 만료가 없다(principal.expiresAt은 자리표시자 Instant.MAX).
                    if (!anonymous) launch { closeOnExpiry() }
                    // 핸드셰이크 필터의 503 검사를 통과한 뒤 drain이 시작됐으면 DrainCoordinator의 snapshot에 없을 수 있다.
                    // 등록 뒤에 다시 확인해서 직접 닫는다(snapshot에도 있으면 close가 한 번만 실행된다).
                    if (runtime.drainState.draining) launch { close(CloseCodes.GOING_AWAY) }
                    guarded { protocol.onOpen(this@SessionRun) }
                    // 동기적으로 변환한다: Reactor Netty는 onNext 직후 프레임 버퍼를 해제한다.
                    session
                        .receive()
                        .map(::toInbound)
                        .asFlow()
                        .collect { onInbound(it) }
                    // inbound 종료 = 연결 종료(상대 또는 우리가 닫음).
                } finally {
                    runtime.registry.unregister(session.id)
                    onEnded(runtime.registry.activeCount)
                    protocol.onClose(this@SessionRun)
                    outbound.close()
                    coroutineContext.cancelChildren()
                }
            }

        /**
         * 종료 메트릭과 로그. 서버가 닫았으면 그 상태를 바로 쓴다. 상대가 닫았으면 받은 close 프레임의 상태를 기다리되
         * (suspend하지 않고 구독), 프레임 없이 끊겼으면 1005(No Status)로 남긴다.
         */
        private fun onEnded(active: Int) {
            val durationNanos = System.nanoTime() - openedAt
            meters.sessionEnded(durationNanos)
            val durationMs = Duration.ofNanos(durationNanos).toMillis()
            val byServer = serverCloseStatus
            if (byServer != null) {
                meters.closed(byServer.code)
                if (SessionLog.enabled) SessionLog.closed(session, principal.userId, byServer, "server", durationMs, active)
                return
            }
            session
                .closeStatus()
                .timeout(CLOSE_STATUS_WAIT)
                .onErrorResume { Mono.empty() }
                .defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
                .subscribe {
                    meters.closed(it.code)
                    if (SessionLog.enabled) SessionLog.closed(session, principal.userId, it, "client", durationMs, active)
                }
        }

        /** 실제로 소켓에 넘긴 데이터 프레임만 센다(ping 제외). 큐에서 버려진 것은 여기 오지 않는다. */
        private fun countOutbound(message: WebSocketMessage) {
            if (message.type == WebSocketMessage.Type.TEXT) meters.messagesOut.increment()
        }

        private fun toInbound(message: WebSocketMessage): Inbound =
            when (message.type) {
                WebSocketMessage.Type.TEXT -> {
                    meters.messagesIn.increment()
                    Inbound.Text(message.payloadAsText)
                }

                WebSocketMessage.Type.PONG -> {
                    Inbound.Pong
                }

                // 프로토콜은 TEXT만 처리하고 BINARY는 무시한다. 그래도 받은 데이터 프레임이므로 센다(게이트웨이와 같은 기준, D27).
                WebSocketMessage.Type.BINARY -> {
                    meters.messagesIn.increment()
                    Inbound.Other
                }

                else -> {
                    Inbound.Other
                }
            }

        private suspend fun onInbound(inbound: Inbound) {
            when (inbound) {
                Inbound.Pong -> pongSeen = true
                Inbound.Other -> Unit
                is Inbound.Text -> guarded { protocol.onText(this, inbound.text) }
            }
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

        /** 프로토콜 코드의 예외: 원인을 한 번 남기고 이 세션만 1011로 닫는다. */
        private suspend fun fail(cause: Throwable) {
            log.warn(cause) { "ws session ${session.id} failed in protocol code, closing with ${CloseCodes.SERVER_ERROR.code}" }
            close(CloseCodes.SERVER_ERROR)
        }

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
            return when {
                result.isSuccess -> WsSessionContext.Offer.SENT
                result.isClosed -> WsSessionContext.Offer.CLOSED
                else -> {
                    // 큐가 가득 참: 느린 소비자
                    meters.messagesDropped.increment()
                    WsSessionContext.Offer.DROPPED
                }
            }
        }

        private suspend fun heartbeat() {
            val interval = props.heartbeat.interval.toMillis()
            var missed = 0
            while (true) {
                delay(interval)
                if (pongSeen) {
                    missed = 0
                } else if (++missed >= props.heartbeat.maxMissed) {
                    close(CloseCodes.HEARTBEAT_TIMEOUT)
                    return
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
         * [run]이 끝나며 자식 코루틴을 취소한다 — 이 코루틴도 포함된다. 쓰기 도중 close Mono가 취소되면
         * Reactor Netty가 CloseWebSocketFrame을 전송 전에 해제해서, 클라이언트는 1001/3000/3008 대신
         * 1006을 받는다(docs/decisions.md D9).
         */
        private suspend fun close(status: CloseStatus) {
            if (!closing.compareAndSet(false, true)) return
            serverCloseStatus = status
            withContext(NonCancellable) { session.close(status).awaitSingleOrNull() }
        }
    }

    private companion object {
        /** 상대가 닫은 경우 close 프레임 상태를 기다리는 최대 시간(메트릭과 로그용). */
        val CLOSE_STATUS_WAIT: Duration = Duration.ofSeconds(1)
    }
}
