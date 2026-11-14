package com.example.wspoc.backend.session

import org.springframework.web.reactive.socket.CloseStatus

/**
 * Every close code the server sends. All must be valid per RFC 6455 §7.4 — a §7.4.1 code that may be put in a
 * Close frame, or the §7.4.2 application range 3000–4999 — so they survive Spring Cloud Gateway's relay unchanged
 * (its WebsocketRoutingFilter rewrites anything else to 1002; docs/decisions.md D13/D14). Enforced by
 * CloseCodesRfc6455Test. Handshake auth failures never get here: they are HTTP 401 before the upgrade.
 */
object CloseCodes {
    /** 3000 (§7.4.2 application range, IANA "Unauthorized"): the access token bound to the session expired. */
    val TOKEN_EXPIRED = CloseStatus(3000, "token expired")

    /** 3008 (§7.4.2 application range, IANA "Timeout"): no pong for `ws.heartbeat.max-missed` consecutive pings. */
    val HEARTBEAT_TIMEOUT = CloseStatus(3008, "heartbeat timeout")

    /** 1001 Going Away (§7.4.1, "a server going down"): the server is draining for shutdown / rollout. */
    val GOING_AWAY: CloseStatus = CloseStatus.GOING_AWAY

    /** 1008 Policy Violation (§7.4.1): defensive only — a session without an authenticated principal. */
    val POLICY_VIOLATION: CloseStatus = CloseStatus.POLICY_VIOLATION

    val ALL = listOf(TOKEN_EXPIRED, HEARTBEAT_TIMEOUT, GOING_AWAY, POLICY_VIOLATION)
}
