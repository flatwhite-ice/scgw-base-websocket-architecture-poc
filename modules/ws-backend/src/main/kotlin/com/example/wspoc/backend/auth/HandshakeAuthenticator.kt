package com.example.wspoc.backend.auth

import com.example.wspoc.auth.common.AuthPrincipal
import com.example.wspoc.auth.common.AuthResult
import com.example.wspoc.auth.common.BearerToken
import com.example.wspoc.auth.common.GatewayHeaders
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.TokenType
import com.example.wspoc.backend.WsProperties
import com.example.wspoc.backend.WsProperties.AuthMode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

@Component
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

    /** 인증된 principal을 반환한다. 핸드셰이크를 401로 거절해야 하면 null. */
    suspend fun authenticate(request: ServerHttpRequest): AuthPrincipal? =
        when (props.auth.mode) {
            AuthMode.GATEWAY -> GatewayHeaders.decode { request.headers.getFirst(it) }
            AuthMode.SELF -> verifySelf(request)
        }

    private suspend fun verifySelf(request: ServerHttpRequest): AuthPrincipal? {
        val jwtVerifier = verifier.getObject()
        // 브라우저는 WebSocket 핸드셰이크에 헤더를 넣을 수 없어서, 수명이 짧은 티켓을 쿼리 문자열로 받는다.
        val ticket = request.queryParams.getFirst("ticket")
        val result =
            if (ticket != null) {
                jwtVerifier.verify(ticket, TokenType.WS_TICKET)
            } else {
                val bearer = BearerToken.extract(request.headers.getFirst(HttpHeaders.AUTHORIZATION)) ?: return null
                jwtVerifier.verify(bearer, TokenType.ACCESS)
            }
        return (result as? AuthResult.Success)?.principal
    }
}
