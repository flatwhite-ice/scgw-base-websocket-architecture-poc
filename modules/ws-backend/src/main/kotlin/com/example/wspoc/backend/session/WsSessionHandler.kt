package com.example.wspoc.backend.session

import com.example.wspoc.auth.common.AuthPrincipal
import com.example.wspoc.backend.WsProperties
import com.example.wspoc.backend.auth.WsPrincipal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
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
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 엔드포인트마다 핸들러 하나. 각 세션은 [SessionScope.dispatcher]에서 코루틴으로 실행된다. Reactor와의
 * 변환은 이 경계에서만 한다(receive -> Flow, Channel -> send Flux, close Mono).
 *
 * 프로토콜(JSON 텍스트 프레임):
 *  - `{"type":"echo",...}`      -> 같은 객체 + `srvTs`, `userId`(인증된 principal), `server`(이 인스턴스)
 *  - `{"type":"subscribe","topic":"ticker","intervalMs":1000,"payloadBytes":256}` -> 주기적 `push` 메시지
 *  - `{"type":"unsubscribe"}`  -> push 중지
 */
class WsSessionHandler(
    private val props: WsProperties,
    private val registry: SessionRegistry,
    private val sessionScope: SessionScope,
    private val jsonMapper: JsonMapper,
    private val serverIdentity: ServerIdentity,
    /** `/ws/push`는 연결 즉시 기본 구독을 시작하고, `/ws/echo`는 명시적인 subscribe를 기다린다. */
    private val autoSubscribe: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : WebSocketHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 세션 송신 큐가 가득 차서(느린 소비자) 버린 push 메시지 수. */
    val droppedPushMessages = AtomicLong()

    override fun handle(session: WebSocketSession): Mono<Void> =
        mono(sessionScope.dispatcher) {
            val principal = (session.handshakeInfo.principal.awaitSingleOrNull() as? WsPrincipal)?.auth
            if (principal == null) {
                // 실제로는 도달하지 않는다: WsHandshakeAuthFilter가 미인증 핸드셰이크를 업그레이드 전에 거절한다.
                session.close(CloseCodes.POLICY_VIOLATION).awaitSingleOrNull()
            } else {
                SessionRun(session, principal).run()
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
        private val principal: AuthPrincipal,
    ) {
        private val outbound =
            Channel<WebSocketMessage>(props.outboundBufferSize) { DataBufferUtils.release(it.payload) }
        private val closing = AtomicBoolean()

        @Volatile
        private var pongSeen = true
        private var pushJob: Job? = null

        suspend fun run() =
            coroutineScope {
                val scope = this
                registry.register(session.id) { status -> scope.launch { close(status) } }
                try {
                    launch { session.send(outbound.consumeAsFlow().asFlux()).awaitSingleOrNull() }
                    launch { heartbeat() }
                    launch { closeOnExpiry() }
                    if (autoSubscribe) {
                        startPush(scope, props.push.defaultTopic, props.push.defaultIntervalMs, props.push.defaultPayloadBytes)
                    }
                    // 동기적으로 변환한다: Reactor Netty는 onNext 직후 프레임 버퍼를 해제한다.
                    session
                        .receive()
                        .map(::toInbound)
                        .asFlow()
                        .collect { onInbound(scope, it) }
                    // inbound 종료 = 연결 종료(상대 또는 우리가 닫음).
                } finally {
                    registry.unregister(session.id)
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

        private suspend fun onInbound(
            scope: CoroutineScope,
            inbound: Inbound,
        ) {
            when (inbound) {
                Inbound.Pong -> pongSeen = true
                Inbound.Other -> Unit
                is Inbound.Text -> onText(scope, inbound.text)
            }
        }

        private suspend fun onText(
            scope: CoroutineScope,
            text: String,
        ) {
            val node =
                try {
                    jsonMapper.readTree(text) as? ObjectNode
                } catch (_: JacksonException) {
                    null
                } ?: return reply(error("invalid json"))

            when (node.path("type").asString()) {
                "echo" -> {
                    node.put("srvTs", clock.millis())
                    node.put("userId", principal.userId)
                    node.put("server", serverIdentity.id)
                    reply(node)
                }

                "subscribe" -> {
                    val push = props.push
                    val interval = node.path("intervalMs").asLong(push.defaultIntervalMs).coerceAtLeast(push.minIntervalMs)
                    val bytes = node.path("payloadBytes").asInt(push.defaultPayloadBytes).coerceIn(0, push.maxPayloadBytes)
                    startPush(scope, node.path("topic").asString(push.defaultTopic), interval, bytes)
                }

                "unsubscribe" -> pushJob?.cancel()

                else -> reply(error("unknown type"))
            }
        }

        /** 응답은 버리지 않고 수신 루프에 backpressure를 건다. */
        private suspend fun reply(node: ObjectNode) {
            if (closing.get()) return
            try {
                outbound.send(session.textMessage(jsonMapper.writeValueAsString(node)))
            } catch (_: ClosedSendChannelException) {
                // 세션이 닫히는 중이다. 늦은 응답은 버린다(버퍼는 onUndeliveredElement가 해제).
            }
        }

        private fun error(reason: String): ObjectNode =
            jsonMapper
                .createObjectNode()
                .put("type", "error")
                .put("reason", reason)
                .put("server", serverIdentity.id)

        private fun startPush(
            scope: CoroutineScope,
            topic: String,
            intervalMs: Long,
            payloadBytes: Int,
        ) {
            pushJob?.cancel()
            pushJob =
                scope.launch {
                    val payload = "x".repeat(payloadBytes)
                    val start = System.nanoTime()
                    var seq = 0L
                    while (isActive) {
                        val message =
                            jsonMapper
                                .createObjectNode()
                                .put("type", "push")
                                .put("topic", topic)
                                .put("seq", seq)
                                .put("srvTs", clock.millis())
                                .put("server", serverIdentity.id)
                                .put("payload", payload)
                        val sent = outbound.trySend(session.textMessage(jsonMapper.writeValueAsString(message)))
                        if (sent.isClosed) return@launch
                        if (sent.isFailure) droppedPushMessages.incrementAndGet() // 큐가 가득 참: 느린 소비자

                        seq++
                        // 고정 주기 스케줄: 처리 시간 때문에 전송 시각이 밀리지 않게 한다.
                        val nextAt = start + Duration.ofMillis(intervalMs * seq).toNanos()
                        delay(Duration.ofNanos(nextAt - System.nanoTime()).toMillis().coerceAtLeast(0))
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
            delay(Duration.between(clock.instant(), principal.expiresAt).toMillis().coerceAtLeast(0))
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
