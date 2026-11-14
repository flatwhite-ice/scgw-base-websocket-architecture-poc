package com.example.wspoc.authapi

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("auth")
data class AuthProperties(
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val ticketTtl: Duration = Duration.ofSeconds(30),
    val defaultScopes: Set<String> = setOf("ws"),
    val signingKey: SigningKey = SigningKey(),
) {
    data class SigningKey(
        val keyId: String = "poc-1",
        /** 암호화되지 않은 PKCS#8 PEM. 지정하지 않으면 기동할 때 새 키 쌍을 만든다. */
        val pemPath: Path? = null,
    )
}
