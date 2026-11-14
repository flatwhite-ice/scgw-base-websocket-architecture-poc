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
                "Use the default config / 'direct' profile, or the 'relay' profile for gateway mode."
        }
    }

    /** Returns the authenticated principal, or null if the handshake must be rejected with 401. */
    suspend fun authenticate(request: ServerHttpRequest): AuthPrincipal? =
        when (props.auth.mode) {
            AuthMode.GATEWAY -> GatewayHeaders.decode { request.headers.getFirst(it) }
            AuthMode.SELF -> verifySelf(request)
        }

    private suspend fun verifySelf(request: ServerHttpRequest): AuthPrincipal? {
        val jwtVerifier = verifier.getObject()
        // Browsers cannot set headers on a WebSocket handshake, so the short-lived ticket comes in the query string.
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
