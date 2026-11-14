package com.example.wspoc.auth.common

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

object RsaKeys {
    fun generate(
        keyId: String,
        bits: Int = 2048,
    ): RSAKey =
        RSAKeyGenerator(bits)
            .keyID(keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate()

    /**
     * Parses an unencrypted PKCS#8 PEM private key (`-----BEGIN PRIVATE KEY-----`, e.g. from
     * `openssl genpkey -algorithm RSA`) using only the JDK, deriving the public key from the CRT parameters.
     */
    fun fromPem(
        pem: String,
        keyId: String,
    ): RSAKey {
        val base64 =
            pem
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
        require(pem.contains("BEGIN PRIVATE KEY")) { "expected an unencrypted PKCS#8 'PRIVATE KEY' PEM block" }
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))) as RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
        return RSAKey
            .Builder(publicKey)
            .privateKey(privateKey)
            .keyID(keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build()
    }
}
