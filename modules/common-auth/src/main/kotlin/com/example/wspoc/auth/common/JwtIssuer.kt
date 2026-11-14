package com.example.wspoc.auth.common

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

/**
 * Issues RS256-signed JWTs with `sub`, `scope`, `exp`, `jti`, `typ` claims.
 *
 * Issuing runs on [randomDispatcher] (default [Dispatchers.IO]): the random `jti` and RSA signing draw from
 * `SecureRandom`, whose default NativePRNG reads `/dev/urandom` — a blocking file read that BlockHound flags
 * on event-loop threads. Verification has no such call and stays on the caller's thread.
 */
class JwtIssuer(
    private val signingKey: RSAKey,
    private val clock: Clock = Clock.systemUTC(),
    private val randomDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(signingKey.isPrivate) { "signing key must contain the private part" }
    }

    private val signer = RSASSASigner(signingKey)

    suspend fun issue(
        userId: String,
        scopes: Set<String>,
        type: TokenType,
        ttl: Duration,
        sessionExpiresAt: Instant? = null,
    ): IssuedToken = withContext(randomDispatcher) { issueBlocking(userId, scopes, type, ttl, sessionExpiresAt) }

    private fun issueBlocking(
        userId: String,
        scopes: Set<String>,
        type: TokenType,
        ttl: Duration,
        sessionExpiresAt: Instant?,
    ): IssuedToken {
        val now = clock.instant()
        // JWT NumericDate has second precision; truncate so IssuedToken.expiresAt matches the `exp` claim exactly.
        val expiresAt = now.plus(ttl).truncatedTo(ChronoUnit.SECONDS)
        val jti = UUID.randomUUID().toString()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(userId)
                .claim(Claims.SCOPE, scopes.sorted().joinToString(" "))
                .claim(Claims.TYPE, type.claim)
                .jwtID(jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .apply { sessionExpiresAt?.let { claim(Claims.SESSION_EXPIRES_AT, it.epochSecond) } }
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        val jwt = SignedJWT(header, claims).apply { sign(signer) }
        return IssuedToken(jwt.serialize(), jti, expiresAt)
    }
}
