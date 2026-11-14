package com.example.wspoc.auth.common

import java.time.Instant

/** `typ` 클레임 값. */
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

    /** WS 티켓 전용: 티켓을 발급받을 때 쓴 access token의 만료 시각(epoch 초). WS 세션 수명의 상한이 된다. */
    const val SESSION_EXPIRES_AT = "sexp"
}
