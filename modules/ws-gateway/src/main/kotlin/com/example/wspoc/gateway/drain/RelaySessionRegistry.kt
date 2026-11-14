package com.example.wspoc.gateway.drain

import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/** Client-facing WebSocket sessions currently relayed by Spring Cloud Gateway (one per connected client). */
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
