package com.example.wspoc.authapi

import com.example.wspoc.auth.token.JwtIssuer
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.RsaKeys
import com.example.wspoc.auth.token.StaticKeySource
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
        // 기동 시 main 스레드에서 한 번만 실행되므로 여기서 파일을 읽어도 된다.
        return cfg.pemPath?.let { RsaKeys.fromPem(Files.readString(it), cfg.keyId) }
            ?: RsaKeys.generate(cfg.keyId).also { log.info("Generated ephemeral RSA signing key kid={}", it.keyID) }
    }

    @Bean
    fun jwtIssuer(signingKey: RSAKey) = JwtIssuer(signingKey)

    /** /auth/ws-ticket에 제시된 access token을 검증한다. 신뢰하는 키는 발급자 자신의 키뿐이다. */
    @Bean
    fun accessTokenVerifier(signingKey: RSAKey) = JwtVerifier(StaticKeySource(signingKey))

    @Bean
    fun publicJwks(signingKey: RSAKey): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()
}
