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
 * [JwtIssuer]가 발급한 RS256 JWT를 검증한다. [KeySource] 조회를 빼면 순수 CPU 작업이므로
 * event loop에서 호출해도 안전하다.
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
        // 티켓은 자신의 `exp` 전에만 교환하면 된다. 그 티켓으로 연 세션은 access token이 만료될 때까지 유지된다.
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
