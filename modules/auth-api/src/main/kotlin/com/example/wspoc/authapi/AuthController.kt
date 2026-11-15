package com.example.wspoc.authapi

import com.example.wspoc.auth.token.BearerToken
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class TokenRequest(
    val userId: String,
    val scopes: Set<String>? = null,
    /** 선택. 테스트 콘솔에서 토큰 만료(3000)를 보기 위한 짧은 TTL. 1초 이상, 기본 TTL 이하만 허용한다(D23). */
    val ttlSeconds: Long? = null,
)

data class TokenResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
)

data class TicketResponse(
    val ticket: String,
    val expiresAt: Instant,
)

@RestController
class AuthController(
    private val authService: AuthService,
    @Qualifier("publicJwks") private val publicJwks: Map<String, Any>,
) {
    @PostMapping("/auth/token")
    suspend fun token(
        @RequestBody request: TokenRequest,
    ): TokenResponse {
        val issued = authService.issueAccessToken(request.userId, request.scopes, request.ttlSeconds)
        return TokenResponse(token = issued.token, expiresAt = issued.expiresAt)
    }

    @PostMapping("/auth/ws-ticket")
    suspend fun wsTicket(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): TicketResponse {
        val token = BearerToken.extract(authorization) ?: throw UnauthenticatedException()
        val issued = authService.issueWsTicket(token)
        return TicketResponse(ticket = issued.token, expiresAt = issued.expiresAt)
    }

    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = publicJwks
}
