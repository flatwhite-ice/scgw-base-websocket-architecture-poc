package com.example.wspoc.wscore.session

import org.springframework.web.reactive.socket.CloseStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 자기 코루틴 밖(drain)에서 닫을 수 있는 살아 있는 세션. */
fun interface SessionHandle {
    fun closeAsync(status: CloseStatus)
}

class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val opened = AtomicLong()

    val activeCount: Int get() = sessions.size

    /** 지금까지 열린 세션 총수(WebSocket 핸들러까지 도달한 핸드셰이크 수). */
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
