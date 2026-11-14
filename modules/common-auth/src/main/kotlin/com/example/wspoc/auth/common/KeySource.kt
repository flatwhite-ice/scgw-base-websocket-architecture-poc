package com.example.wspoc.auth.common

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey

/** `kid`로 검증용 공개키를 찾는다. 구현체는 블로킹하면 안 된다. */
fun interface KeySource {
    suspend fun find(keyId: String?): RSAKey?
}

/** 고정된 키 집합. 예: 발급자 자신의 키, 로컬에 설정한 키. */
class StaticKeySource(
    keys: List<RSAKey>,
) : KeySource {
    constructor(vararg keys: RSAKey) : this(keys.toList())

    private val jwkSet = JWKSet(keys.map { it.toPublicJWK() })

    override suspend fun find(keyId: String?): RSAKey? = jwkSet.selectRsa(keyId)
}

internal fun JWKSet.selectRsa(keyId: String?): RSAKey? {
    val rsaKeys = keys.filterIsInstance<RSAKey>()
    return if (keyId == null) rsaKeys.singleOrNull() else rsaKeys.firstOrNull { it.keyID == keyId }
}
