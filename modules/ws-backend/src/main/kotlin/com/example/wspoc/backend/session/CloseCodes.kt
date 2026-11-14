package com.example.wspoc.backend.session

import org.springframework.web.reactive.socket.CloseStatus

/**
 * Close codes sent by the server. All are IANA-registered (WebSocket Close Code Number Registry), not private-use
 * 4xxx codes; see docs/decisions.md D13. Handshake auth failures never get here: they are HTTP 401 before upgrade.
 */
object CloseCodes {
    /** 3000 Unauthorized: the access token bound to the session expired. Client should re-authenticate and reconnect. */
    val TOKEN_EXPIRED = CloseStatus(3000, "token expired")

    /** 3008 Timeout: no pong for `ws.heartbeat.max-missed` consecutive pings. */
    val HEARTBEAT_TIMEOUT = CloseStatus(3008, "heartbeat timeout")

    /** 1012 Service Restart: server is draining (shutdown / rollout). Client should reconnect, with backoff + jitter. */
    val SERVICE_RESTART: CloseStatus = CloseStatus.SERVICE_RESTARTED
}
