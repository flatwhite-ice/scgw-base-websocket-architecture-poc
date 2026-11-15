package com.example.wspoc.gateway

import com.example.wspoc.auth.token.AuthPrincipal
import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.auth.token.BearerToken
import com.example.wspoc.auth.token.FailureReason
import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.TokenType
import com.example.wspoc.metrics.HandshakeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/**
 * 라우트 필터 `WsAuth`: 이 필터를 선언한 라우트만 인증한다(D20). 인증이 필요한 라우트는 YAML에 이렇게 적는다.
 * ```yaml
 * routes:
 *   - id: ws-backend
 *     filters:
 *       - WsAuth
 * ```
 * - `?ticket=`(1회용) 또는 `Authorization: Bearer`를 검증한다. 이 라우트의 요청은 Upgrade 여부와 관계없이 모두 인증한다.
 * - 실패하면 401로 응답하고 chain을 호출하지 않으므로 backend로 가는 upstream 연결이 생기지 않는다.
 * - 성공하면 신원 헤더(X-User-Id, X-Scopes, X-Auth-Exp)를 넣고, 전달할 요청에서 `ticket`과 `Authorization`을 제거한다.
 *
 * 클라이언트가 보낸 신원 헤더 제거와 drain 503은 모든 라우트에 적용되는 [GatewayEdgeFilter]가 맡는다.
 * 헤더 제거가 반드시 주입보다 먼저 실행되도록 [ORDER]를 Edge 바로 뒤로 고정한다.
 */
@Component
class WsAuthGatewayFilterFactory(
    private val verifier: JwtVerifier,
) : AbstractGatewayFilterFactory<WsAuthGatewayFilterFactory.Config>(Config::class.java) {
    /** 설정 항목은 아직 없다(`- WsAuth`). 필요한 scope 같은 조건을 나중에 여기에 둔다. */
    class Config

    override fun apply(config: Config): GatewayFilter =
        OrderedGatewayFilter(
            { exchange, chain ->
                mono(Dispatchers.Unconfined) {
                    when (val result = authenticate(exchange.request)) {
                        is AuthResult.Failure -> {
                            GatewayEdgeFilter
                                .handshakeTrace(exchange)
                                ?.record(HandshakeResult.UNAUTHORIZED, result.reason.name.lowercase())
                            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                            exchange.response.setComplete().awaitSingleOrNull()
                        }
                        is AuthResult.Success -> {
                            val forwarded = exchange.mutate().request(forwardedRequest(exchange.request, result.principal)).build()
                            chain.filter(forwarded).awaitSingleOrNull()
                        }
                    }
                }.then()
            },
            ORDER,
        )

    private suspend fun authenticate(request: ServerHttpRequest): AuthResult {
        val ticket = request.queryParams.getFirst(TICKET_PARAM)
        return if (ticket != null) {
            verifier.verify(ticket, TokenType.WS_TICKET)
        } else {
            val bearer = BearerToken.extract(request.headers.getFirst(HttpHeaders.AUTHORIZATION)) ?: return MISSING
            verifier.verify(bearer, TokenType.ACCESS)
        }
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

    companion object {
        /** [GatewayEdgeFilter.ORDER] 바로 뒤: 클라이언트 신원 헤더를 지운 다음에 진짜 신원을 넣는다. */
        const val ORDER = GatewayEdgeFilter.ORDER + 1
        private const val TICKET_PARAM = "ticket"
        private val MISSING = AuthResult.Failure(FailureReason.MISSING)
    }
}
