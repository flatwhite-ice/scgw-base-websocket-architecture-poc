package com.example.wspoc.backend.auth

import com.example.wspoc.backend.session.DrainState
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Authenticates `/ws/` handshakes before the upgrade: on failure the client gets a plain HTTP 401 and no
 * WebSocket session is ever created. While draining, new handshakes get 503.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WsHandshakeAuthFilter(
    private val authenticator: HandshakeAuthenticator,
    private val drainState: DrainState,
) : CoWebFilter() {
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

        if (drainState.draining) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE)
        val principal = authenticator.authenticate(exchange.request) ?: return reject(exchange, HttpStatus.UNAUTHORIZED)
        // Exposed to the WebSocket handler as HandshakeInfo.principal (no WebSession needed).
        chain.filter(exchange.mutate().principal(Mono.just(WsPrincipal(principal))).build())
    }

    private suspend fun reject(
        exchange: ServerWebExchange,
        status: HttpStatus,
    ) {
        exchange.response.statusCode = status
        // Completing the response here (without calling the chain) is what keeps the upgrade from happening.
        exchange.response.setComplete().awaitSingleOrNull()
    }

    companion object {
        const val WS_PATH_PREFIX = "/ws/"
    }
}
