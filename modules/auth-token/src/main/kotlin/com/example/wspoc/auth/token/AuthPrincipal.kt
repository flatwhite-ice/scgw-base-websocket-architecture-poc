package com.example.wspoc.auth.token

import java.time.Instant

data class AuthPrincipal(
    val userId: String,
    val scopes: Set<String>,
    val expiresAt: Instant,
)
