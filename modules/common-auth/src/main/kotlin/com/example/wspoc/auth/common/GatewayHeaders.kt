package com.example.wspoc.auth.common

import java.time.Instant

/**
 * Headers the gateway injects after authenticating a handshake (relay mode). The backend trusts them only in
 * `gateway` auth mode, and the gateway always strips client-supplied copies to prevent spoofing.
 */
object GatewayHeaders {
    const val USER_ID = "X-User-Id"
    const val SCOPES = "X-Scopes"

    /** Session expiry as epoch seconds, so the backend can close with 4401 in relay mode too. */
    const val AUTH_EXP = "X-Auth-Exp"

    val ALL = listOf(USER_ID, SCOPES, AUTH_EXP)

    fun encode(principal: AuthPrincipal): Map<String, String> =
        mapOf(
            USER_ID to principal.userId,
            SCOPES to principal.scopes.sorted().joinToString(" "),
            AUTH_EXP to principal.expiresAt.epochSecond.toString(),
        )

    fun decode(header: (String) -> String?): AuthPrincipal? {
        val userId = header(USER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = header(AUTH_EXP)?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val scopes =
            header(SCOPES)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        return AuthPrincipal(userId, scopes, expiresAt)
    }
}
