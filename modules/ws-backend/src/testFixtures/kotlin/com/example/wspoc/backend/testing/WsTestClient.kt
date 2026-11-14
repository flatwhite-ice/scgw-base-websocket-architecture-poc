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
 * [ReactorNettyWebSocketClient]를 감싼 얇은 테스트용 래퍼.
 *
 * @param answerPings true(기본)면 Reactor Netty가 서버 ping에 자동으로 응답한다. false면 클라이언트가
 * ping을 받기만 하고 응답하지 않아 죽은 상대를 흉내 낸다.
 */
class WsTestClient(
    answerPings: Boolean = true,
) {
    private val client =
        ReactorNettyWebSocketClient(HttpClient.create()) {
            // handlePing=true면 pong을 자동으로 보내지 않고 ping을 애플리케이션으로 넘긴다.
            WebsocketClientSpec.builder().handlePing(!answerPings)
        }

    /** 연결을 열고 핸드셰이크를 기다린다. 서버가 업그레이드를 거절하면(예: 401) 예외를 던진다. */
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
                    // close 프레임 없이 연결이 끝남(closeStatus()가 값 없이 완료): 1005로 기록한다.
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
