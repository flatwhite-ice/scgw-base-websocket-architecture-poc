package com.example.wspoc.backend.auth

import com.example.wspoc.auth.common.AuthPrincipal
import java.security.Principal

/** Adapts [AuthPrincipal] to [Principal] so it travels with the exchange into `HandshakeInfo.principal`. */
data class WsPrincipal(
    val auth: AuthPrincipal,
) : Principal {
    override fun getName(): String = auth.userId
}
