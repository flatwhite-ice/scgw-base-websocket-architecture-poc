package com.example.wspoc.wscore.auth

import com.example.wspoc.auth.common.AuthPrincipal
import java.security.Principal
import java.time.Instant

/**
 * [AuthPrincipal]을 [Principal]로 감싸서 exchange와 함께 `HandshakeInfo.principal`까지 전달되게 한다.
 * [anonymous]는 `@WsEndpoint(auth = NONE)` 엔드포인트의 세션이다(D20). 이때 [auth]는 자리표시자다.
 */
data class WsPrincipal(
    val auth: AuthPrincipal,
    val anonymous: Boolean = false,
) : Principal {
    override fun getName(): String = auth.userId

    companion object {
        private val ANONYMOUS = WsPrincipal(AuthPrincipal("anonymous", emptySet(), Instant.MAX), anonymous = true)

        fun anonymous(): WsPrincipal = ANONYMOUS
    }
}
