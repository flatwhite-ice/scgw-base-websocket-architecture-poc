package com.example.wspoc.wscore.testing

import com.example.wspoc.auth.common.JwtIssuer
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.auth.common.RsaKeys
import com.example.wspoc.auth.common.StaticKeySource
import com.example.wspoc.auth.common.TokenType
import java.time.Duration

/** 테스트가 공유하는 서명 키. auth-api 대신 쓴다. */
object TestKeys {
    val key = RsaKeys.generate("test-key")
    val issuer = JwtIssuer(key)
    val keySource: KeySource = StaticKeySource(key)

    suspend fun accessToken(
        userId: String,
        ttl: Duration = Duration.ofMinutes(15),
    ): String = issuer.issue(userId, setOf("ws"), TokenType.ACCESS, ttl).token

    suspend fun ticket(
        userId: String,
        sessionTtl: Duration = Duration.ofMinutes(15),
    ): String {
        val sessionExpiresAt =
            java.time.Instant
                .now()
                .plus(sessionTtl)
        return issuer.issue(userId, setOf("ws"), TokenType.WS_TICKET, Duration.ofSeconds(30), sessionExpiresAt).token
    }
}
