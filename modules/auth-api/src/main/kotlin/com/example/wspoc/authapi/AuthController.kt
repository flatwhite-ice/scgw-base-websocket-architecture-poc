package com.example.wspoc.authapi

import com.example.wspoc.auth.common.AuthResult
import com.example.wspoc.auth.common.BearerToken
import com.example.wspoc.auth.common.JwtIssuer
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.TokenType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class TokenRequest(
    val userId: String,
    val scopes: Set<String>? = null,
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
    /** PoC: no password, any non-blank userId gets a token. */
    @PostMapping("/auth/token")
    suspend fun token(
        @RequestBody request: TokenRequest,
    ): TokenResponse {
        if (request.userId.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required")
        val issued = issuer.issue(request.userId, request.scopes ?: props.defaultScopes, TokenType.ACCESS, props.accessTokenTtl)
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
