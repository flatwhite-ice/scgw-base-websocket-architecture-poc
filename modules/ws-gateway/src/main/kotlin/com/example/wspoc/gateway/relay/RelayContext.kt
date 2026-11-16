package com.example.wspoc.gateway.relay

import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.support.session.SessionLogFormat
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.web.server.ServerWebExchange
import java.net.URI

/**
 * relay 세션 하나가 어디서 와서 어디로 가는지. 업그레이드 시점의 exchange에서 한 번 뽑아 둔다.
 * 세션 로그([RelaySessionLog])와 메트릭의 `route` 태그([routeId])에 쓴다.
 */
data class RelayContext(
    val path: String,
    val routeId: String,
    /** `WsAuth`가 넣은 신원. 익명 라우트면 `-`. */
    val userId: String,
    /** LoadBalancer가 고른 backend(`host:port`). */
    val upstream: String,
    val remote: String,
) {
    companion object {
        fun of(exchange: ServerWebExchange): RelayContext {
            val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
            val upstream = exchange.getAttribute<URI>(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)
            return RelayContext(
                // 쿼리는 남기지 않는다(`ticket`). WsAuth 라우트는 이미 지웠지만 익명 라우트는 그대로일 수 있다.
                path = exchange.request.path.value(),
                routeId = route?.id ?: SessionLogFormat.NONE,
                userId = exchange.request.headers.getFirst(GatewayHeaders.USER_ID) ?: SessionLogFormat.NONE,
                upstream = upstream?.let { "${it.host}:${it.port}" } ?: SessionLogFormat.NONE,
                remote = SessionLogFormat.hostPort(exchange.request.remoteAddress),
            )
        }
    }
}
