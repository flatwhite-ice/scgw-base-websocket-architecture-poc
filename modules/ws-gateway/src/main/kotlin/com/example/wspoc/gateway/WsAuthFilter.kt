package com.example.wspoc.gateway

import com.example.wspoc.auth.common.AuthPrincipal
import com.example.wspoc.auth.common.AuthResult
import com.example.wspoc.auth.common.BearerToken
import com.example.wspoc.auth.common.GatewayHeaders
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.TokenType
import com.example.wspoc.gateway.drain.GatewayDrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

/**
 * 엣지에서 WebSocket 업그레이드를 인증한다(relay 구조).
 *
 * - 클라이언트가 보낸 신원 헤더(X-User-Id, X-Scopes, X-Auth-Exp)는 위조를 막기 위해 항상 제거한다.
 * - Upgrade 요청: `?ticket=`(1회용) 또는 `Authorization: Bearer`를 검증한다. 실패하면 401로 응답하고
 *   chain을 호출하지 않으므로 ws-backend로 가는 upstream 연결이 생기지 않는다.
 * - 성공하면 신원 헤더를 넣고, 전달할 URI에서 `ticket`을 제거한다.
 * - drain 중에는 새 업그레이드에 503을 준다.
 */
@Component
class WsAuthFilter(
    private val verifier: JwtVerifier,
    private val drainState: GatewayDrainState,
) : GlobalFilter,
    Ordered {
    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> =
        mono(Dispatchers.Unconfined) {
            val request = exchange.request
            if (!isWebSocketUpgrade(request)) {
                return@mono chain.filter(exchange.withRequest(stripIdentityHeaders(request))).awaitSingleOrNull()
            }
            if (drainState.draining) return@mono reject(exchange, HttpStatus.SERVICE_UNAVAILABLE)
            val principal = authenticate(request) ?: return@mono reject(exchange, HttpStatus.UNAUTHORIZED)
            chain.filter(exchange.withRequest(forwardedRequest(request, principal))).awaitSingleOrNull()
        }.then()

    private suspend fun authenticate(request: ServerHttpRequest): AuthPrincipal? {
        val ticket = request.queryParams.getFirst(TICKET_PARAM)
        val result =
            if (ticket != null) {
                verifier.verify(ticket, TokenType.WS_TICKET)
            } else {
                val bearer = BearerToken.extract(request.headers.getFirst(HttpHeaders.AUTHORIZATION)) ?: return null
                verifier.verify(bearer, TokenType.ACCESS)
            }
        return (result as? AuthResult.Success)?.principal
    }

    private fun forwardedRequest(
        request: ServerHttpRequest,
        principal: AuthPrincipal,
    ): ServerHttpRequest {
        // backend의 URI(와 그쪽 access log)에 티켓이 남지 않게 한다.
        val uri =
            UriComponentsBuilder
                .fromUri(request.uri)
                .replaceQueryParam(TICKET_PARAM)
                .build(true)
                .toUri()
        return request
            .mutate()
            .uri(uri)
            .headers { headers ->
                GatewayHeaders.ALL.forEach(headers::remove)
                headers.remove(HttpHeaders.AUTHORIZATION)
                GatewayHeaders.encode(principal).forEach(headers::set)
            }.build()
    }

    private fun stripIdentityHeaders(request: ServerHttpRequest): ServerHttpRequest =
        if (GatewayHeaders.ALL.none(request.headers::containsHeader)) {
            request
        } else {
            request.mutate().headers { headers -> GatewayHeaders.ALL.forEach(headers::remove) }.build()
        }

    private suspend fun reject(
        exchange: ServerWebExchange,
        status: HttpStatus,
    ) {
        exchange.response.statusCode = status
        exchange.response.setComplete().awaitSingleOrNull()
    }

    private fun ServerWebExchange.withRequest(request: ServerHttpRequest): ServerWebExchange =
        if (request === this.request) this else mutate().request(request).build()

    private fun isWebSocketUpgrade(request: ServerHttpRequest) = request.headers.upgrade.equals("websocket", ignoreCase = true)

    private companion object {
        const val TICKET_PARAM = "ticket"
    }
}
