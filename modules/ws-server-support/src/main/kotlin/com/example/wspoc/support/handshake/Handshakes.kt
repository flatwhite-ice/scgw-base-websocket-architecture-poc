package com.example.wspoc.support.handshake

import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.metrics.HandshakeTrace
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/** 핸드셰이크 요청에서 1회용 WS 티켓을 받는 쿼리 파라미터 이름(`?ticket=`). */
const val TICKET_PARAM = "ticket"

/**
 * 핸드셰이크 요청의 자격증명을 검증한다. 브라우저는 WebSocket 핸드셰이크에 헤더를 넣을 수 없어서
 * 수명이 짧은 티켓을 쿼리 문자열로 받고, 그 밖의 클라이언트는 `Authorization: Bearer`도 쓸 수 있다.
 * 서비스(self 모드)와 게이트웨이가 같은 규칙을 쓴다.
 */
suspend fun JwtVerifier.verifyHandshake(request: ServerHttpRequest): AuthResult =
    verifyHandshake(
        ticket = request.queryParams.getFirst(TICKET_PARAM),
        authorizationHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION),
    )

/** 진행 중인 핸드셰이크의 측정(D27). 필터가 [HandshakeTrace.ATTRIBUTE]에 넣어 둔 것을 꺼낸다. 없으면 null. */
val ServerWebExchange.handshakeTrace: HandshakeTrace?
    get() = getAttribute<HandshakeTrace>(HandshakeTrace.ATTRIBUTE)

/**
 * [status]로 응답을 끝낸다. chain을 호출하지 않고 여기서 끝내므로 업그레이드가 일어나지 않고,
 * 게이트웨이에서는 upstream 연결도 열리지 않는다.
 */
fun ServerWebExchange.completeWith(status: HttpStatus): Mono<Void> {
    response.statusCode = status
    return response.setComplete()
}
