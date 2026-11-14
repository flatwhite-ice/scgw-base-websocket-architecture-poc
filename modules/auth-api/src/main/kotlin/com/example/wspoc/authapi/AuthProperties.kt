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
        /** Unencrypted PKCS#8 PEM. When unset, a fresh key pair is generated at startup. */
        val pemPath: Path? = null,
    )
}
