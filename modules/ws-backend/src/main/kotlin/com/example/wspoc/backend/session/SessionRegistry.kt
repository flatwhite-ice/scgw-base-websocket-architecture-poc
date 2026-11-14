package com.example.wspoc.backend.session

import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A live session that can be closed from outside its own coroutine (drain). */
fun interface SessionHandle {
    fun closeAsync(status: CloseStatus)
}

@Component
class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val opened = AtomicLong()

    val activeCount: Int get() = sessions.size

    /** Total sessions ever opened (handshakes that reached the WebSocket handler). */
    val openedCount: Long get() = opened.get()

    fun register(
        id: String,
        handle: SessionHandle,
    ) {
        sessions[id] = handle
        opened.incrementAndGet()
    }

    fun unregister(id: String) {
        sessions.remove(id)
    }

    fun snapshot(): Map<String, SessionHandle> = HashMap(sessions)
}
