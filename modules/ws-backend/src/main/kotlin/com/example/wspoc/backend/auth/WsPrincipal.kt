package com.example.wspoc.backend.auth

import com.example.wspoc.auth.common.AuthPrincipal
import java.security.Principal

/** [AuthPrincipal]을 [Principal]로 감싸서 exchange와 함께 `HandshakeInfo.principal`까지 전달되게 한다. */
data class WsPrincipal(
    val auth: AuthPrincipal,
) : Principal {
    override fun getName(): String = auth.userId
}
