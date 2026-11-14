package com.example.wspoc.auth.common

object BearerToken {
    private const val PREFIX = "Bearer "

    /** Extracts the token from an `Authorization: Bearer <token>` header value, or null. */
    fun extract(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true) }
            ?.substring(PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
