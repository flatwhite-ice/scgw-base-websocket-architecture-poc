package com.example.wspoc.authapi

import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.auth.token.BearerToken
import com.example.wspoc.auth.token.JwtIssuer
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.TokenType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
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
    private val issuer: JwtIssuer,
    private val accessTokenVerifier: JwtVerifier,
    private val props: AuthProperties,
    @Qualifier("publicJwks") private val publicJwks: Map<String, Any>,
) {
    /** PoC: 비밀번호 없이, 비어 있지 않은 userId면 토큰을 발급한다. */
    @PostMapping("/auth/token")
    suspend fun token(
        @RequestBody request: TokenRequest,
    ): TokenResponse {
        if (request.userId.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required")
        val ttl =
            request.ttlSeconds?.let {
                if (it < 1 || it > props.accessTokenTtl.seconds) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ttlSeconds must be 1..${props.accessTokenTtl.seconds}")
                }
                Duration.ofSeconds(it)
            } ?: props.accessTokenTtl
        val issued = issuer.issue(request.userId, request.scopes ?: props.defaultScopes, TokenType.ACCESS, ttl)
        return TokenResponse(token = issued.token, expiresAt = issued.expiresAt)
    }

    @PostMapping("/auth/ws-ticket")
    suspend fun wsTicket(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): TicketResponse {
        val token = BearerToken.extract(authorization) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val principal =
            when (val result = accessTokenVerifier.verify(token, TokenType.ACCESS)) {
                is AuthResult.Success -> result.principal
                is AuthResult.Failure -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, result.reason.name)
            }
        val issued =
            issuer.issue(
                principal.userId,
                principal.scopes,
                TokenType.WS_TICKET,
                props.ticketTtl,
                sessionExpiresAt = principal.expiresAt,
            )
        return TicketResponse(ticket = issued.token, expiresAt = issued.expiresAt)
    }

    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = publicJwks
}
