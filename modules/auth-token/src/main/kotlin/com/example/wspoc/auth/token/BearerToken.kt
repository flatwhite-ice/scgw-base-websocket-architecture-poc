package com.example.wspoc.auth.token

object BearerToken {
    private const val PREFIX = "Bearer "

    /** `Authorization: Bearer <token>` 헤더 값에서 토큰을 꺼낸다. 없으면 null. */
    fun extract(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true) }
            ?.substring(PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
