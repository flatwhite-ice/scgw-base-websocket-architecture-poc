package com.example.wspoc.auth.common

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey

/** Resolves public verification keys by `kid`. Implementations must not block. */
fun interface KeySource {
    suspend fun find(keyId: String?): RSAKey?
}

/** Fixed set of keys, e.g. the issuer's own key or a key configured locally. */
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
