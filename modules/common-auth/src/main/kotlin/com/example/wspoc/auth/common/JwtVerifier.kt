package com.example.wspoc.auth.common

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Verifies RS256 JWTs issued by [JwtIssuer]. Pure CPU work apart from [KeySource] lookups,
 * so it is safe to call from an event loop.
 */
class JwtVerifier(
    private val keySource: KeySource,
    private val replayGuard: TicketReplayGuard = TicketReplayGuard(),
    private val clock: Clock = Clock.systemUTC(),
    private val clockSkew: Duration = Duration.ZERO,
) {
    suspend fun verify(
        token: String,
        expected: TokenType,
    ): AuthResult {
        val jwt =
            try {
                SignedJWT.parse(token)
            } catch (_: ParseException) {
                return fail(FailureReason.MALFORMED)
            }
        if (jwt.header.algorithm != JWSAlgorithm.RS256) return fail(FailureReason.BAD_SIGNATURE)

        val key = keySource.find(jwt.header.keyID) ?: return fail(FailureReason.UNKNOWN_KEY)
        val signatureValid =
            try {
                jwt.verify(RSASSAVerifier(key))
            } catch (_: JOSEException) {
                false
            }
        if (!signatureValid) return fail(FailureReason.BAD_SIGNATURE)

        val claims =
            try {
                jwt.jwtClaimsSet
            } catch (_: ParseException) {
                return fail(FailureReason.MALFORMED)
            }
        val userId = claims.subject
        val expiresAt = claims.expirationTime?.toInstant()
        if (userId.isNullOrBlank() || expiresAt == null) return fail(FailureReason.MALFORMED)
        if (!clock.instant().isBefore(expiresAt.plus(clockSkew))) return fail(FailureReason.EXPIRED)
        if (TokenType.fromClaim(claims.getStringClaim(Claims.TYPE)) != expected) return fail(FailureReason.WRONG_TYPE)

        if (expected == TokenType.WS_TICKET) {
            val jti = claims.jwtid ?: return fail(FailureReason.MALFORMED)
            if (!replayGuard.tryConsume(jti, expiresAt)) return fail(FailureReason.REPLAYED)
        }

        val scopes =
            claims
                .getStringClaim(Claims.SCOPE)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        // A ticket only has to be redeemed before its own `exp`; the session it opens lives until the access token expires.
        val sessionExpiresAt =
            if (expected == TokenType.WS_TICKET) {
                claims.getLongClaim(Claims.SESSION_EXPIRES_AT)?.let(Instant::ofEpochSecond) ?: expiresAt
            } else {
                expiresAt
            }
        return AuthResult.Success(AuthPrincipal(userId, scopes, sessionExpiresAt))
    }

    private fun fail(reason: FailureReason) = AuthResult.Failure(reason)
}
