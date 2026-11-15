package com.example.wspoc.gateway.relay

import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/** Spring Cloud Gateway가 현재 relay 중인 클라이언트 쪽 WebSocket 세션(연결된 클라이언트마다 하나). */
@Component
class RelaySessionRegistry {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    val activeCount: Int get() = sessions.size

    fun register(session: WebSocketSession) {
        sessions[session.id] = session
    }

    fun unregister(id: String) {
        sessions.remove(id)
    }

    fun snapshot(): List<WebSocketSession> = sessions.values.toList()
}
