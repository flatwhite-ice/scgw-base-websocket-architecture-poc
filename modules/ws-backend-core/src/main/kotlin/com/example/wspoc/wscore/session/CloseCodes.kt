package com.example.wspoc.wscore.session

import org.springframework.web.reactive.socket.CloseStatus

/**
 * 서버가 보내는 모든 close code. 모두 RFC 6455 §7.4 기준이어야 한다: Close 프레임에 실을 수 있는 §7.4.1 코드
 * 또는 §7.4.2 애플리케이션 범위 3000–4999. 그래야 Spring Cloud Gateway를 거쳐도 그대로 전달된다
 * (WebsocketRoutingFilter는 그 외 코드를 1002로 바꾼다. docs/decisions.md D13/D14). CloseCodesRfc6455Test가
 * 이 규칙을 검증한다. 핸드셰이크 인증 실패는 여기 오지 않는다: 업그레이드 전에 HTTP 401로 처리된다.
 */
object CloseCodes {
    /** 3000 (§7.4.2 애플리케이션 범위, IANA "Unauthorized"): 세션에 묶인 access token이 만료됨. */
    val TOKEN_EXPIRED = CloseStatus(3000, "token expired")

    /** 3008 (§7.4.2 애플리케이션 범위, IANA "Timeout"): ping에 연속 `ws.heartbeat.max-missed`회 pong이 없음. */
    val HEARTBEAT_TIMEOUT = CloseStatus(3008, "heartbeat timeout")

    /** 1001 Going Away (§7.4.1, "a server going down"): 종료/배포를 위해 서버가 drain 중. */
    val GOING_AWAY: CloseStatus = CloseStatus.GOING_AWAY

    /** 1008 Policy Violation (§7.4.1): 방어용 — 인증된 principal이 없는 세션. */
    val POLICY_VIOLATION: CloseStatus = CloseStatus.POLICY_VIOLATION

    val ALL = listOf(TOKEN_EXPIRED, HEARTBEAT_TIMEOUT, GOING_AWAY, POLICY_VIOLATION)
}
