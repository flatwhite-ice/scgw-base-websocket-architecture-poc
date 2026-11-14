package com.example.wspoc.backend.session

import org.springframework.web.reactive.socket.CloseStatus

object CloseCodes {
    /** Token (access token bound to the session) expired; client should re-authenticate and reconnect. */
    val TOKEN_EXPIRED = CloseStatus(4401, "token expired")

    /** No pong for `ws.heartbeat.max-missed` consecutive pings. */
    val HEARTBEAT_TIMEOUT = CloseStatus(4408, "heartbeat timeout")

    /** Server is draining (shutdown / rollout). */
    val GOING_AWAY: CloseStatus = CloseStatus.GOING_AWAY
}
