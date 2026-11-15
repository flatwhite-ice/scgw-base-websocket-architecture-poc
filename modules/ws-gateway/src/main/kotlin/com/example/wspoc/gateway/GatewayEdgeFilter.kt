package com.example.wspoc.gateway

import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.gateway.relay.GatewayDrainState
import com.example.wspoc.metrics.HandshakeResult
import com.example.wspoc.metrics.HandshakeTrace
import com.example.wspoc.metrics.WsMeters
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
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
 * - 업그레이드 요청이면 `ws.handshake`를 잰다(D27). drain 503은 여기서, 401은 `WsAuth`가, 성공은 클라이언트 쪽 업그레이드가
 *   끝났을 때 `RelaySessionTracking`이 기록한다. 체인은 클라이언트에 101이 나간 시점에 완료되므로(SCG는 upstream을 그 뒤에 연다, D14)
 *   그때까지 기록이 없으면 `error`다.
 *
 * GlobalFilter는 매칭된 라우트에서만 실행된다. 라우트가 없는 경로는 이 필터 전에 404가 된다.
 */
@Component
class GatewayEdgeFilter(
    private val drainState: GatewayDrainState,
    private val meters: WsMeters,
) : GlobalFilter,
    Ordered {
    override fun getOrder() = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        if (!request.headers.upgrade.equals("websocket", ignoreCase = true)) return chain.filter(stripIdentity(exchange))

        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val trace = meters.startHandshake(route?.id ?: WsMeters.UNKNOWN_SCOPE)
        if (drainState.draining) {
            trace.record(HandshakeResult.DRAINING)
            exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
            return exchange.response.setComplete()
        }
        exchange.attributes[HandshakeTrace.ATTRIBUTE] = trace
        // 401이나 성공은 이미 기록됐다(처음 기록만 남는다).
        return chain.filter(stripIdentity(exchange)).doFinally { trace.record(HandshakeResult.ERROR) }
    }

    private fun stripIdentity(exchange: ServerWebExchange): ServerWebExchange {
        val request = exchange.request
        if (GatewayHeaders.ALL.none(request.headers::containsHeader)) return exchange
        val stripped = request.mutate().headers { headers -> GatewayHeaders.ALL.forEach(headers::remove) }.build()
        return exchange.mutate().request(stripped).build()
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE
    }
}
