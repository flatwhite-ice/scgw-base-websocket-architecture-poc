package com.example.wspoc.backend.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin test wrapper around [ReactorNettyWebSocketClient].
 *
 * @param answerPings when true (default) Reactor Netty replies to server pings automatically; when false the
 * client swallows pings without answering, simulating a dead peer.
 */
class WsTestClient(
    answerPings: Boolean = true,
) {
    private val client =
        ReactorNettyWebSocketClient(HttpClient.create()) {
            // handlePing=true forwards pings to the application instead of auto-replying with a pong.
            WebsocketClientSpec.builder().handlePing(!answerPings)
        }

    /** Opens a connection and waits for the handshake. Throws if the server rejects the upgrade (e.g. 401). */
    suspend fun connect(
        uri: URI,
        headers: HttpHeaders = HttpHeaders(),
        timeout: Duration = Duration.ofSeconds(5),
    ): WsTestConnection {
        val connection = WsTestConnection()
        connection.subscription =
            client
                .execute(uri, headers) { session -> connection.bind(session) }
                .subscribe(
                    {},
                    { error ->
                        connection.opened.completeExceptionally(error)
                        connection.closeStatus.completeExceptionally(error)
                    },
                )
        withTimeout(timeout.toMillis()) { connection.opened.await() }
        return connection
    }
}

class WsTestConnection internal constructor() {
    private val mapper = JsonMapper.builder().build()
    private val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
    internal val opened = CompletableDeferred<Unit>()
    internal var subscription: Disposable? = null

    val received = Channel<String>(Channel.UNLIMITED)
    val pingsReceived = AtomicInteger()
    val closeStatus = CompletableDeferred<CloseStatus>()

    internal fun bind(session: org.springframework.web.reactive.socket.WebSocketSession): Mono<Void> {
        session.closeStatus().subscribe { closeStatus.complete(it) }
        val receive =
            session
                .receive()
                .doOnNext { message ->
                    when (message.type) {
                        WebSocketMessage.Type.TEXT -> received.trySend(message.payloadAsText)
                        WebSocketMessage.Type.PING -> pingsReceived.incrementAndGet()
                        else -> Unit
                    }
                }.doFinally {
                    outbound.tryEmitComplete()
                    // Connection ended without a close frame (closeStatus() completes empty): record 1005.
                    session.closeStatus().defaultIfEmpty(CloseStatus.NO_STATUS_CODE).subscribe { closeStatus.complete(it) }
                }.then()
        val send = session.send(outbound.asFlux().map(session::textMessage))
        opened.complete(Unit)
        return Mono.zip(send, receive).then()
    }

    fun send(text: String) {
        outbound.tryEmitNext(text).orThrow()
    }

    fun sendJson(vararg fields: Pair<String, Any?>) = send(mapper.writeValueAsString(mapOf(*fields)))

    suspend fun nextJson(timeout: Duration = Duration.ofSeconds(5)): JsonNode =
        withTimeout(timeout.toMillis()) { mapper.readTree(received.receive()) }

    suspend fun awaitClose(timeout: Duration = Duration.ofSeconds(5)): CloseStatus = withTimeout(timeout.toMillis()) { closeStatus.await() }

    fun close() {
        outbound.tryEmitComplete()
        subscription?.dispose()
    }
}
