package com.example.wspoc.authapi

import com.example.wspoc.auth.token.AuthResult
import com.example.wspoc.auth.token.IssuedToken
import com.example.wspoc.auth.token.JwtIssuer
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.TokenType
import org.springframework.stereotype.Service
import java.time.Duration

/** 토큰 발급 규칙. HTTP 관련 처리(본문, 헤더 파싱)는 컨트롤러가 맡는다. */
@Service
class AuthService(
    private val issuer: JwtIssuer,
    private val accessTokenVerifier: JwtVerifier,
    private val props: AuthProperties,
) {
    /** PoC: 비밀번호 없이, 비어 있지 않은 userId면 access token을 발급한다. */
    suspend fun issueAccessToken(
        userId: String,
        scopes: Set<String>?,
        ttlSeconds: Long?,
    ): IssuedToken {
        if (userId.isBlank()) throw InvalidTokenRequestException("userId is required")
        return issuer.issue(userId, scopes ?: props.defaultScopes, TokenType.ACCESS, accessTokenTtl(ttlSeconds))
    }

    /** access token을 검증하고, 그 만료 시각을 세션 수명 상한(`sexp`)으로 담은 1회용 티켓을 발급한다. */
    suspend fun issueWsTicket(accessToken: String): IssuedToken {
        val principal =
            when (val result = accessTokenVerifier.verify(accessToken, TokenType.ACCESS)) {
                is AuthResult.Success -> result.principal
                is AuthResult.Failure -> throw UnauthenticatedException(result.reason.name)
            }
        return issuer.issue(
            principal.userId,
            principal.scopes,
            TokenType.WS_TICKET,
            props.ticketTtl,
            sessionExpiresAt = principal.expiresAt,
        )
    }

    /** 선택 TTL은 1초 이상, 기본 TTL 이하만 허용한다(D23). 없으면 기본 TTL이다. */
    private fun accessTokenTtl(ttlSeconds: Long?): Duration {
        val max = props.accessTokenTtl
        if (ttlSeconds == null) return max
        if (ttlSeconds < 1 || ttlSeconds > max.seconds) {
            throw InvalidTokenRequestException("ttlSeconds must be 1..${max.seconds}")
        }
        return Duration.ofSeconds(ttlSeconds)
    }
}
