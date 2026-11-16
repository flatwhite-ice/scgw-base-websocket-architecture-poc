package com.example.wspoc.wscore.auth

import com.example.wspoc.metrics.HandshakeResult
import com.example.wspoc.support.handshake.handshakeTrace
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 업그레이드가 끝난 시점에 `ws.handshake`를 `ok`로 기록한다(D27).
 * `handleRequest`의 Mono는 업그레이드 응답(101)이 나가면 완료되고 실패하면 오류로 끝난다. 응답 상태 코드로는 알 수 없다:
 * Reactor Netty는 101을 핸드셰이커가 직접 쓰고 응답 객체의 상태는 그대로 둔다.
 */
class HandshakeMeteringWebSocketService(
    private val delegate: WebSocketService,
) : WebSocketService {
    override fun handleRequest(
        exchange: ServerWebExchange,
        handler: WebSocketHandler,
    ): Mono<Void> = delegate.handleRequest(exchange, handler).doOnSuccess { exchange.handshakeTrace?.record(HandshakeResult.OK) }
}
