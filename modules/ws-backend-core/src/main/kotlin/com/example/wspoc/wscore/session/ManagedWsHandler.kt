package com.example.wspoc.wscore.session

import com.example.wspoc.auth.common.AuthPrincipal
import com.example.wspoc.wscore.WsProperties
import com.example.wspoc.wscore.auth.WsPrincipal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 모든 [ManagedWsHandler]가 공유하는 런타임 의존성. 서비스는 이 빈 하나만 주입받으면 된다. */
class WsSessionRuntime(
    val props: WsProperties,
    val registry: SessionRegistry,
    val sessionScope: SessionScope,
    val serverIdentity: ServerIdentity,
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
 *  - close는 취소되지 않게 보낸다(D9)
 *
 * 각 세션은 [SessionScope.dispatcher]에서 코루틴으로 실행된다. Reactor와의 변환은 이 경계에서만 한다
 * (receive -> Flow, Channel -> send Flux, close Mono).
 */
class ManagedWsHandler(
    private val runtime: WsSessionRuntime,
    private val protocolFactory: () -> WsSessionProtocol,
) : WebSocketHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val props = runtime.props

    /** 세션 송신 큐가 가득 차서(느린 소비자) [WsSessionContext.trySend]가 버린 메시지 수. */
    val droppedMessages = AtomicLong()

    override fun handle(session: WebSocketSession): Mono<Void> =
        mono(runtime.sessionScope.dispatcher) {
            val principal = session.handshakeInfo.principal.awaitSingleOrNull() as? WsPrincipal
            if (principal == null) {
                // 실제로는 도달하지 않는다: WsHandshakeAuthFilter가 미인증 핸드셰이크를 업그레이드 전에 거절한다.
                session.close(CloseCodes.POLICY_VIOLATION).awaitSingleOrNull()
            } else {
                SessionRun(session, principal.auth, principal.anonymous).run()
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
    ) : WsSessionContext {
        private val outbound =
            Channel<WebSocketMessage>(props.outboundBufferSize) { DataBufferUtils.release(it.payload) }
        private val closing = AtomicBoolean()
        private val protocol = protocolFactory()

        @Volatile
        private var pongSeen = true

        override val id: String get() = session.id
        override val serverId: String get() = runtime.serverIdentity.id
        override lateinit var scope: CoroutineScope

        suspend fun run() =
            coroutineScope {
                scope = this
                runtime.registry.register(session.id) { status -> launch { close(status) } }
                try {
                    launch { session.send(outbound.consumeAsFlow().asFlux()).awaitSingleOrNull() }
                    launch { heartbeat() }
                    // 익명 세션에는 만료가 없다(principal.expiresAt은 자리표시자 Instant.MAX).
                    if (!anonymous) launch { closeOnExpiry() }
                    protocol.onOpen(this@SessionRun)
                    // 동기적으로 변환한다: Reactor Netty는 onNext 직후 프레임 버퍼를 해제한다.
                    session
                        .receive()
                        .map(::toInbound)
                        .asFlow()
                        .collect { onInbound(it) }
                    // inbound 종료 = 연결 종료(상대 또는 우리가 닫음).
                } finally {
                    runtime.registry.unregister(session.id)
                    protocol.onClose(this@SessionRun)
                    outbound.close()
                    coroutineContext.cancelChildren()
                }
            }

        private fun toInbound(message: WebSocketMessage): Inbound =
            when (message.type) {
                WebSocketMessage.Type.TEXT -> Inbound.Text(message.payloadAsText)
                WebSocketMessage.Type.PONG -> Inbound.Pong
                else -> Inbound.Other
            }

        private suspend fun onInbound(inbound: Inbound) {
            when (inbound) {
                Inbound.Pong -> pongSeen = true
                Inbound.Other -> Unit
                is Inbound.Text -> protocol.onText(this, inbound.text)
            }
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
                    droppedMessages.incrementAndGet()
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
            log.debug("Closing session {} user={} status={}", session.id, principal.userId, status)
            withContext(NonCancellable) { session.close(status).awaitSingleOrNull() }
        }
    }
}
