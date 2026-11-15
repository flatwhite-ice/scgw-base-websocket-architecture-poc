package com.example.wspoc.auth.token

import java.time.Instant

/**
 * 게이트웨이가 핸드셰이크를 인증한 뒤 넣는 헤더(relay 모드). backend는 `gateway` 인증 모드에서만 이 헤더를
 * 신뢰하며, 게이트웨이는 위조를 막기 위해 클라이언트가 보낸 같은 이름의 헤더를 항상 제거한다.
 */
object GatewayHeaders {
    const val USER_ID = "X-User-Id"
    const val SCOPES = "X-Scopes"

    /** 세션 만료 시각(epoch 초). relay 모드에서도 backend가 3000(토큰 만료)으로 닫을 수 있게 한다. */
    const val AUTH_EXP = "X-Auth-Exp"

    val ALL = listOf(USER_ID, SCOPES, AUTH_EXP)

    fun encode(principal: AuthPrincipal): Map<String, String> =
        mapOf(
            USER_ID to principal.userId,
            SCOPES to principal.scopes.sorted().joinToString(" "),
            AUTH_EXP to principal.expiresAt.epochSecond.toString(),
        )

    fun decode(header: (String) -> String?): AuthPrincipal? {
        val userId = header(USER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = header(AUTH_EXP)?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val scopes =
            header(SCOPES)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        return AuthPrincipal(userId, scopes, expiresAt)
    }
}
