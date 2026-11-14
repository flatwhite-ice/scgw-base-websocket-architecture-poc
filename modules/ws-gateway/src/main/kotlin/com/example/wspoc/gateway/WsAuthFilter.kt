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
 * Authenticates WebSocket upgrades at the edge (relay architecture).
 *
 * - Always strips client-supplied identity headers (X-User-Id, X-Scopes, X-Auth-Exp) so they cannot be spoofed.
 * - On Upgrade requests: verifies `?ticket=` (single use) or `Authorization: Bearer`. On failure it answers 401
 *   and never calls the chain, so no upstream connection to ws-backend is opened.
 * - On success it injects the identity headers and removes `ticket` from the forwarded URI.
 * - While draining, new upgrades get 503.
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
        // Keep the ticket out of the backend's URI (and any access log there).
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
