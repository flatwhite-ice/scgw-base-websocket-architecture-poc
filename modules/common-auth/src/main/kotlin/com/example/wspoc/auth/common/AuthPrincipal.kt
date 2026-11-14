package com.example.wspoc.auth.common

import java.time.Instant

data class AuthPrincipal(
    val userId: String,
    val scopes: Set<String>,
    val expiresAt: Instant,
)
