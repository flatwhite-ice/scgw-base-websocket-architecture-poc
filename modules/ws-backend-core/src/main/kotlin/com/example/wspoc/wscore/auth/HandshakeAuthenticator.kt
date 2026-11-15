package com.example.wspoc.wscore.auth

import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.wscore.WsProperties
import com.example.wspoc.wscore.WsProperties.AuthMode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest

class HandshakeAuthenticator(
    private val props: WsProperties,
    private val verifier: ObjectProvider<JwtVerifier>,
) {
    init {
        check(props.auth.mode != AuthMode.SELF || verifier.ifAvailable != null) {
            "ws.auth.mode=self requires a non-blank ws.auth.jwks-uri (or a KeySource bean). " +
                "Use the 'direct' profile, or the default (relay / gateway mode)."
        }
    }

    /** 인증 결과. [AuthResult.Failure]면 핸드셰이크를 401로 거절한다(사유는 메트릭의 `reason` 태그로만 쓴다). */
    suspend fun authenticate(request: ServerHttpRequest): AuthResult =
        when (props.auth.mode) {
            // 게이트웨이 뒤에서는 헤더가 없거나 깨진 경우를 구분하지 않는다. 둘 다 게이트웨이를 거치지 않은 요청이다.
            AuthMode.GATEWAY -> GatewayHeaders.decode { request.headers.getFirst(it) }?.let(AuthResult::Success) ?: AuthResult.MISSING

            // 브라우저는 WebSocket 핸드셰이크에 헤더를 넣을 수 없어서, 수명이 짧은 티켓을 쿼리 문자열로도 받는다.
            AuthMode.SELF ->
                verifier.getObject().verifyHandshake(
                    ticket = request.queryParams.getFirst("ticket"),
                    authorizationHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION),
                )
        }
}
