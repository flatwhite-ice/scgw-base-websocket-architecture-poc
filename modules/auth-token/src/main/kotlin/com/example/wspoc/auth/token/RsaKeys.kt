package com.example.wspoc.auth.token

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
     * 암호화되지 않은 PKCS#8 PEM 개인키(`-----BEGIN PRIVATE KEY-----`, 예: `openssl genpkey -algorithm RSA`로
     * 생성)를 JDK만으로 파싱한다. 공개키는 CRT 파라미터에서 계산한다.
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
