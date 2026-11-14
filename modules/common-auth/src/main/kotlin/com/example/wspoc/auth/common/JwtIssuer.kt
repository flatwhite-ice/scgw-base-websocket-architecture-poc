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
 * `sub`, `scope`, `exp`, `jti`, `typ` 클레임을 담은 RS256 서명 JWT를 발급한다.
 *
 * 발급은 [randomDispatcher](기본 [Dispatchers.IO])에서 실행한다. 무작위 `jti`와 RSA 서명이
 * `SecureRandom`을 쓰는데, 기본 NativePRNG가 `/dev/urandom`을 읽는다. 이 파일 읽기는 블로킹이라
 * event loop 스레드에서 BlockHound에 걸린다. 검증에는 이런 호출이 없어 호출한 스레드에서 그대로 실행한다.
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
        // JWT NumericDate는 초 단위다. IssuedToken.expiresAt이 `exp` 클레임과 정확히 같도록 초 단위로 자른다.
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
