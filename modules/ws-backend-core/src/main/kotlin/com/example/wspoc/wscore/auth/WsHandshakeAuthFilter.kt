package com.example.wspoc.wscore.auth

import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.metrics.HandshakeResult
import com.example.wspoc.metrics.HandshakeTrace
import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.support.drain.DrainState
import com.example.wspoc.support.handshake.completeWith
import com.example.wspoc.wscore.endpoint.WsEndpointHandlerMapping
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * `/ws/` 핸드셰이크를 업그레이드 전에 인증한다. 실패하면 클라이언트는 일반 HTTP 401을 받고
 * WebSocket 세션은 아예 만들어지지 않는다. drain 중에는 새 핸드셰이크에 503을 준다(익명 엔드포인트 포함).
 * `@WsEndpoint(auth = NONE)` 경로는 인증을 건너뛰고 익명 principal로 연다. 자격증명이나 신원 헤더가 와도 쓰지 않는다(D20).
 *
 * `ws.handshake` 메트릭도 여기서 잰다(D27). 거절(401/503)은 이 필터가 기록하고, 성공은 업그레이드가 끝났을 때
 * [HandshakeMeteringWebSocketService]가 기록한다. chain은 업그레이드 응답이 나간 시점에 완료되므로(세션은 그 뒤에 따로 돈다),
 * 그때까지 기록이 없으면 세션이 열리지 않은 것이다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class WsHandshakeAuthFilter(
    private val authenticator: HandshakeAuthenticator,
    private val drainState: DrainState,
    private val endpoints: WsEndpointHandlerMapping,
    private val meters: WsMeters,
) : CoWebFilter() {
    init {
        // 아직 연결이 없는 엔드포인트도 0으로 보이게 미리 등록한다(버린 메시지 수 포함).
        endpoints.endpoints.keys.forEach { meters.scope(it).messagesDropped }
    }

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        if (!exchange.request.path
                .value()
                .startsWith(WS_PATH_PREFIX)
        ) {
            return chain.filter(exchange)
        }

        val path = exchange.request.path.pathWithinApplication()
        val trace = meters.startHandshake(endpoints.endpointOf(path) ?: WsMeters.UNKNOWN_SCOPE)
        if (drainState.draining) {
            trace.record(HandshakeResult.DRAINING)
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE)
        }
        val principal =
            if (endpoints.isAnonymous(path)) {
                WsPrincipal.anonymous(trace.scope)
            } else {
                when (val result = authenticator.authenticate(exchange.request)) {
                    is AuthResult.Success -> WsPrincipal(result.principal, endpoint = trace.scope)
                    is AuthResult.Failure -> {
                        trace.record(HandshakeResult.UNAUTHORIZED, result.reason.name.lowercase())
                        return reject(exchange, HttpStatus.UNAUTHORIZED)
                    }
                }
            }
        exchange.attributes[HandshakeTrace.ATTRIBUTE] = trace
        try {
            // WebSocket 핸들러에는 HandshakeInfo.principal로 전달된다(WebSession 불필요).
            chain.filter(exchange.mutate().principal(Mono.just(principal)).build())
        } finally {
            // 업그레이드됐다면 이미 ok로 기록됐다(처음 기록만 남는다). 그 밖은 없는 경로(404), 업그레이드가 아닌 요청, 업그레이드 실패다.
            trace.record(HandshakeResult.ERROR)
        }
    }

    /** chain을 호출하지 않고 여기서 응답을 끝내기 때문에 업그레이드가 일어나지 않는다. */
    private suspend fun reject(
        exchange: ServerWebExchange,
        status: HttpStatus,
    ) {
        exchange.completeWith(status).awaitSingleOrNull()
    }

    companion object {
        const val WS_PATH_PREFIX = "/ws/"
    }
}
