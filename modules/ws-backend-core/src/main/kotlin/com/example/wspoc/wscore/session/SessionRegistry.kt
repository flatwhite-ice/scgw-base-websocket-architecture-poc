package com.example.wspoc.wscore.session

import com.example.wspoc.support.drain.DrainTarget
import com.example.wspoc.support.drain.DrainableSession
import org.springframework.web.reactive.socket.CloseStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 자기 코루틴 밖(drain)에서 닫을 수 있는 살아 있는 세션. */
fun interface SessionHandle {
    fun closeAsync(status: CloseStatus)
}

/** 이 인스턴스에 열려 있는 세션. drain([DrainTarget])이 여기서 닫을 대상을 얻는다. */
class SessionRegistry : DrainTarget {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val opened = AtomicLong()

    override val activeCount: Int get() = sessions.size

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

    /** 세션 코루틴에 close를 맡긴다. close 프레임을 지키는 일(NonCancellable, D9)은 그 세션이 한다. */
    override fun drainableSessions(): Collection<DrainableSession> =
        sessions.values.map { handle -> DrainableSession { handle.closeAsync(CloseCodes.GOING_AWAY) } }
}
