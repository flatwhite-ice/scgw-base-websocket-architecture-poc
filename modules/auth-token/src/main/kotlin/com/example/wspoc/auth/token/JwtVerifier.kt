package com.example.wspoc.auth.token

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
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
    /**
     * WebSocket 핸드셰이크의 자격증명을 검증한다. [ticket](`?ticket=`)이 있으면 1회용 티켓으로,
     * 없으면 [authorizationHeader]의 Bearer access token으로 본다. 둘 다 없으면 [AuthResult.MISSING]이다.
     */
    suspend fun verifyHandshake(
        ticket: String?,
        authorizationHeader: String?,
    ): AuthResult {
        if (ticket != null) return verify(ticket, TokenType.WS_TICKET)
        val bearer = BearerToken.extract(authorizationHeader) ?: return AuthResult.MISSING
        return verify(bearer, TokenType.ACCESS)
    }

    suspend fun verify(
        token: String,
        expected: TokenType,
    ): AuthResult {
        val jwt = parse(token) ?: return fail(FailureReason.MALFORMED)
        if (jwt.header.algorithm != JWSAlgorithm.RS256) return fail(FailureReason.BAD_SIGNATURE)
        val key = keySource.find(jwt.header.keyID) ?: return fail(FailureReason.UNKNOWN_KEY)
        if (!jwt.hasValidSignature(key)) return fail(FailureReason.BAD_SIGNATURE)

        val claims = jwt.claimsOrNull() ?: return fail(FailureReason.MALFORMED)
        val userId = claims.subject
        val expiresAt = claims.expirationTime?.toInstant()
        if (userId.isNullOrBlank() || expiresAt == null) return fail(FailureReason.MALFORMED)
        if (!clock.instant().isBefore(expiresAt.plus(clockSkew))) return fail(FailureReason.EXPIRED)
        if (TokenType.fromClaim(claims.getStringClaim(Claims.TYPE)) != expected) return fail(FailureReason.WRONG_TYPE)

        var sessionExpiresAt: Instant = expiresAt
        if (expected == TokenType.WS_TICKET) {
            val jti = claims.jwtid ?: return fail(FailureReason.MALFORMED)
            if (!replayGuard.tryConsume(jti, expiresAt)) return fail(FailureReason.REPLAYED)
            // 티켓은 자신의 `exp` 전에만 교환하면 된다. 그 티켓으로 연 세션은 access token이 만료될 때까지 유지된다.
            claims.getLongClaim(Claims.SESSION_EXPIRES_AT)?.let { sessionExpiresAt = Instant.ofEpochSecond(it) }
        }
        val scopes = AuthPrincipal.parseScopes(claims.getStringClaim(Claims.SCOPE))
        return AuthResult.Success(AuthPrincipal(userId, scopes, sessionExpiresAt))
    }

    private fun parse(token: String): SignedJWT? =
        try {
            SignedJWT.parse(token)
        } catch (_: ParseException) {
            null
        }

    private fun SignedJWT.hasValidSignature(key: RSAKey): Boolean =
        try {
            verify(RSASSAVerifier(key))
        } catch (_: JOSEException) {
            false
        }

    private fun SignedJWT.claimsOrNull(): JWTClaimsSet? =
        try {
            jwtClaimsSet
        } catch (_: ParseException) {
            null
        }

    private fun fail(reason: FailureReason) = AuthResult.Failure(reason)
}
