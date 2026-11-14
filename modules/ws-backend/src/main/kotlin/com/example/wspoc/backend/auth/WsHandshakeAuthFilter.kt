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
 * `/ws/` 핸드셰이크를 업그레이드 전에 인증한다. 실패하면 클라이언트는 일반 HTTP 401을 받고
 * WebSocket 세션은 아예 만들어지지 않는다. drain 중에는 새 핸드셰이크에 503을 준다.
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
        // WebSocket 핸들러에는 HandshakeInfo.principal로 전달된다(WebSession 불필요).
        chain.filter(exchange.mutate().principal(Mono.just(WsPrincipal(principal))).build())
    }

    private suspend fun reject(
        exchange: ServerWebExchange,
        status: HttpStatus,
    ) {
        exchange.response.statusCode = status
        // chain을 호출하지 않고 여기서 응답을 끝내기 때문에 업그레이드가 일어나지 않는다.
        exchange.response.setComplete().awaitSingleOrNull()
    }

    companion object {
        const val WS_PATH_PREFIX = "/ws/"
    }
}
