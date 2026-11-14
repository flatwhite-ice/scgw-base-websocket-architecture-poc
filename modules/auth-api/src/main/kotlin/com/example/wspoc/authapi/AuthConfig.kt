package com.example.wspoc.authapi

import com.example.wspoc.auth.common.JwtIssuer
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.RsaKeys
import com.example.wspoc.auth.common.StaticKeySource
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class AuthConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun signingKey(props: AuthProperties): RSAKey {
        val cfg = props.signingKey
        // Runs once on the main thread during startup, so reading the file here is fine.
        return cfg.pemPath?.let { RsaKeys.fromPem(Files.readString(it), cfg.keyId) }
            ?: RsaKeys.generate(cfg.keyId).also { log.info("Generated ephemeral RSA signing key kid={}", it.keyID) }
    }

    @Bean
    fun jwtIssuer(signingKey: RSAKey) = JwtIssuer(signingKey)

    /** Validates access tokens presented to /auth/ws-ticket; the issuer's own key is the only trusted key. */
    @Bean
    fun accessTokenVerifier(signingKey: RSAKey) = JwtVerifier(StaticKeySource(signingKey))

    @Bean
    fun publicJwks(signingKey: RSAKey): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()
}
