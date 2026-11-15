package com.example.wspoc.gateway.relay

import com.example.wspoc.metrics.WsMeters
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ws.relay.upstream.connect`: SCG가 upstream으로 WebSocket을 여는 데 걸린 시간과 결과.
 * SCG는 클라이언트에 101을 먼저 응답하고 upstream을 나중에 열기 때문에(D14), upstream 연결 실패는 핸드셰이크 결과가 아니라
 * 여기에 `result=error`로 나타난다(클라이언트는 "101 뒤 1002"를 본다). 포트 고갈(E3)과 죽은 backend(E4)를 볼 때 쓴다.
 *
 * 라우트 id는 [TrackingWebSocketService]가 Reactor context에 넣어 준다. SCG의 프록시 핸들러가 `client.execute(...)`의
 * Mono를 그대로 돌려주므로 context가 여기까지 전달된다.
 */
class TrackingWebSocketClient(
    private val delegate: WebSocketClient,
    private val meters: WsMeters,
) : WebSocketClient {
    override fun execute(
        url: URI,
        handler: WebSocketHandler,
    ): Mono<Void> = execute(url, HttpHeaders(), handler)

    override fun execute(
        url: URI,
        headers: HttpHeaders,
        handler: WebSocketHandler,
    ): Mono<Void> =
        Mono.deferContextual { context ->
            val route = context.getOrDefault(RelaySessionTracking.ROUTE_CONTEXT_KEY, WsMeters.UNKNOWN_SCOPE)!!
            val startedAt = System.nanoTime()
            // 연결 결과는 한 번만 기록한다: 핸들러가 불리면 성공이고, 그 전에 난 오류만 연결 실패다.
            // 연결된 뒤의 오류는 relay 도중의 일이라 세지 않는다.
            val recorded = AtomicBoolean()

            fun record(error: Throwable?) {
                if (recorded.compareAndSet(false, true)) meters.upstreamConnect(route, error, System.nanoTime() - startedAt)
            }
            val onConnected =
                object : WebSocketHandler {
                    override fun getSubProtocols(): List<String> = handler.subProtocols

                    override fun handle(session: WebSocketSession): Mono<Void> {
                        record(null)
                        return handler.handle(session)
                    }
                }
            delegate.execute(url, headers, onConnected).doOnError(::record)
        }
}
