package com.example.wspoc.wscore.auth

import com.example.wspoc.auth.token.AuthPrincipal
import com.example.wspoc.metrics.WsMeters
import java.security.Principal
import java.time.Instant

/**
 * [AuthPrincipal]을 [Principal]로 감싸서 exchange와 함께 `HandshakeInfo.principal`까지 전달되게 한다.
 * [anonymous]는 `@WsEndpoint(auth = NONE)` 엔드포인트의 세션이다(D20). 이때 [auth]는 자리표시자다.
 * [endpoint]는 메트릭 태그용이다(D27): 필터가 찾은 `@WsEndpoint` 선언 경로를 핸들러까지 가져간다.
 * exchange에서 WebSocket 핸들러로 넘어가는 것은 principal뿐이라 여기에 싣는다.
 */
data class WsPrincipal(
    val auth: AuthPrincipal,
    val anonymous: Boolean = false,
    val endpoint: String = WsMeters.UNKNOWN_SCOPE,
) : Principal {
    override fun getName(): String = auth.userId

    companion object {
        private val ANONYMOUS = WsPrincipal(AuthPrincipal("anonymous", emptySet(), Instant.MAX), anonymous = true)

        fun anonymous(endpoint: String = WsMeters.UNKNOWN_SCOPE): WsPrincipal = ANONYMOUS.copy(endpoint = endpoint)
    }
}
