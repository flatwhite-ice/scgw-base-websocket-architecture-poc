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
 * One handler per endpoint. Each session runs as a coroutine on [SessionScope.dispatcher]; the only Reactor
 * interop is at this boundary (receive -> Flow, Channel -> send Flux, close Mono).
 *
 * Protocol (JSON text frames):
 *  - `{"type":"echo",...}`      -> same object + `srvTs`, `userId` (authenticated principal), `server` (this instance)
 *  - `{"type":"subscribe","topic":"ticker","intervalMs":1000,"payloadBytes":256}` -> periodic `push` messages
 *  - `{"type":"unsubscribe"}`  -> stop pushing
 */
class WsSessionHandler(
    private val props: WsProperties,
    private val registry: SessionRegistry,
    private val sessionScope: SessionScope,
    private val jsonMapper: JsonMapper,
    private val serverIdentity: ServerIdentity,
    /** `/ws/push` starts the default subscription on connect; `/ws/echo` waits for an explicit subscribe. */
    private val autoSubscribe: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : WebSocketHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Push messages dropped because a session's outbound queue was full (slow consumer). */
    val droppedPushMessages = AtomicLong()

    override fun handle(session: WebSocketSession): Mono<Void> =
        mono(sessionScope.dispatcher) {
            val principal = (session.handshakeInfo.principal.awaitSingleOrNull() as? WsPrincipal)?.auth
            if (principal == null) {
                // Unreachable in practice: WsHandshakeAuthFilter rejects unauthenticated handshakes before the upgrade.
                session.close(CloseStatus.POLICY_VIOLATION).awaitSingleOrNull()
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
                    // Map synchronously: Reactor Netty releases the frame buffer right after onNext.
                    session
                        .receive()
                        .map(::toInbound)
                        .asFlow()
                        .collect { onInbound(scope, it) }
                    // Inbound completed => connection closed (by peer or by us).
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

        /** Replies apply backpressure to the receive loop instead of being dropped. */
        private suspend fun reply(node: ObjectNode) {
            if (closing.get()) return
            try {
                outbound.send(session.textMessage(jsonMapper.writeValueAsString(node)))
            } catch (_: ClosedSendChannelException) {
                // Session is closing; late replies are discarded (buffer released by onUndeliveredElement).
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
                        if (sent.isFailure) droppedPushMessages.incrementAndGet() // queue full: slow consumer

                        seq++
                        // Fixed-rate schedule so delivery does not drift with processing time.
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
         * Sends the close frame. Must not be cancelled: Reactor Netty's `sendClose()` immediately ends the inbound
         * side, which completes [run] and cancels its children — including this coroutine. Cancelling the close
         * Mono mid-write makes Reactor Netty release the CloseWebSocketFrame before it is flushed, and the client
         * sees 1006 instead of 1001/4401/4408 (docs/decisions.md D9).
         */
        private suspend fun close(status: CloseStatus) {
            if (!closing.compareAndSet(false, true)) return
            log.debug("Closing session {} user={} status={}", session.id, principal.userId, status)
            withContext(NonCancellable) { session.close(status).awaitSingleOrNull() }
        }
    }
}
