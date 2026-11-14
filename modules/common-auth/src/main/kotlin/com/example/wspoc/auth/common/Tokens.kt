package com.example.wspoc.auth.common

import java.time.Instant

/** Value of the `typ` claim. */
enum class TokenType(
    val claim: String,
) {
    ACCESS("access"),
    WS_TICKET("ws-ticket"),
    ;

    companion object {
        fun fromClaim(value: String?): TokenType? = entries.firstOrNull { it.claim == value }
    }
}

data class IssuedToken(
    val token: String,
    val jti: String,
    val expiresAt: Instant,
)

sealed interface AuthResult {
    data class Success(
        val principal: AuthPrincipal,
    ) : AuthResult

    data class Failure(
        val reason: FailureReason,
    ) : AuthResult
}

enum class FailureReason {
    MALFORMED,
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    EXPIRED,
    WRONG_TYPE,
    REPLAYED,
}

object Claims {
    const val SCOPE = "scope"
    const val TYPE = "typ"

    /** On WS tickets: expiry (epoch seconds) of the access token the ticket was exchanged for. Bounds the WS session. */
    const val SESSION_EXPIRES_AT = "sexp"
}
