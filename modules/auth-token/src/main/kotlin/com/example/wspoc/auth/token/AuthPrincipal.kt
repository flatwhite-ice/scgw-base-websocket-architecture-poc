package com.example.wspoc.auth.token

import java.time.Instant

data class AuthPrincipal(
    val userId: String,
    val scopes: Set<String>,
    val expiresAt: Instant,
) {
    companion object {
        /** 공백으로 구분한 scope 문자열(JWT `scope` 클레임, `X-Scopes` 헤더)을 집합으로 바꾼다. */
        fun parseScopes(value: String?): Set<String> =
            value
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
    }
}
