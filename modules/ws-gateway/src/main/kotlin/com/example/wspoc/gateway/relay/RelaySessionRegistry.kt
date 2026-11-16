package com.example.wspoc.gateway.relay

import com.example.wspoc.support.drain.DrainTarget
import com.example.wspoc.support.drain.DrainableSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring Cloud Gateway가 현재 relay 중인 클라이언트 쪽 WebSocket 세션(연결된 클라이언트마다 하나).
 * drain([DrainTarget])이 여기서 닫을 대상을 얻는다.
 */
@Component
class RelaySessionRegistry : DrainTarget {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    override val activeCount: Int get() = sessions.size

    fun register(session: WebSocketSession) {
        sessions[session.id] = session
    }

    fun unregister(id: String) {
        sessions.remove(id)
    }

    /**
     * 클라이언트 쪽만 닫으면 된다: SCG가 close를 backend 세션에도 전달한다(1001은 RFC 6455 코드라 바뀌지 않는다, D14).
     * 취소 불가로 감싼다: close Mono가 취소되면 프레임이 전송되기 전에 해제된다(D9).
     */
    override fun drainableSessions(): Collection<DrainableSession> =
        sessions.values.map { session ->
            DrainableSession { withContext(NonCancellable) { session.close(CloseStatus.GOING_AWAY).awaitSingleOrNull() } }
        }
}
