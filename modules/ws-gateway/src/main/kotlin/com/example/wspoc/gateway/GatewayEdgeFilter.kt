package com.example.wspoc.gateway

import com.example.wspoc.auth.common.GatewayHeaders
import com.example.wspoc.gateway.drain.GatewayDrainState
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 모든 라우트(인증 여부와 무관)에 적용되는 엣지 처리(D20). 인증 자체는 라우트 필터 `WsAuth`가 한다.
 *
 * - 클라이언트가 보낸 신원 헤더(X-User-Id, X-Scopes, X-Auth-Exp)를 항상 제거한다. 익명 라우트에서도 위조 헤더가
 *   backend로 가면 안 된다. 또 `WsAuth`를 빠뜨린 라우트가 있어도, 신원 헤더가 없으니 backend(gateway 모드)가
 *   인증 엔드포인트를 거절한다(fail-closed).
 * - drain 중에는 새 WebSocket 업그레이드에 503을 준다. 게이트웨이 인스턴스 단위라 라우트와 무관하다.
 *
 * GlobalFilter는 매칭된 라우트에서만 실행된다. 라우트가 없는 경로는 이 필터 전에 404가 된다.
 */
@Component
class GatewayEdgeFilter(
    private val drainState: GatewayDrainState,
) : GlobalFilter,
    Ordered {
    override fun getOrder() = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        if (drainState.draining && request.headers.upgrade.equals("websocket", ignoreCase = true)) {
            exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
            return exchange.response.setComplete()
        }
        if (GatewayHeaders.ALL.none(request.headers::containsHeader)) return chain.filter(exchange)
        val stripped = request.mutate().headers { headers -> GatewayHeaders.ALL.forEach(headers::remove) }.build()
        return chain.filter(exchange.mutate().request(stripped).build())
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE
    }
}
