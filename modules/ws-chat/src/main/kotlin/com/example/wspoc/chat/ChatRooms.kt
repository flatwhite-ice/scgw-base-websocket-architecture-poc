package com.example.wspoc.chat

import com.example.wspoc.wscore.session.WsSessionContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 방 이름 -> 참여 세션. **이 인스턴스 안에서만** 공유된다: ws-chat을 여러 대 띄우면 같은 방 이름이라도
 * 인스턴스마다 멤버가 따로다(인스턴스 간 fan-out은 후속 과제, D18).
 */
class ChatRooms {
    private val rooms = ConcurrentHashMap<String, MutableSet<WsSessionContext>>()

    fun join(
        room: String,
        session: WsSessionContext,
    ) {
        rooms.computeIfAbsent(room) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun leave(
        room: String,
        session: WsSessionContext,
    ) {
        // compute로 원자적으로 지워야 빈 방을 지우는 순간 들어온 join이 사라지지 않는다.
        rooms.computeIfPresent(room) { _, members -> members.apply { remove(session) }.ifEmpty { null } }
    }

    /**
     * 방의 모든 세션에 보낸다. 느린 소비자 때문에 다른 멤버가 막히지 않도록 기다리지 않는다(trySend):
     * 큐가 가득 찬 세션의 메시지는 버려진다. 반환값은 실제로 큐에 넣은 세션 수.
     */
    fun broadcast(
        room: String,
        text: String,
    ): Int = rooms[room].orEmpty().count { it.trySend(text) == WsSessionContext.Offer.SENT }

    fun members(room: String): Int = rooms[room]?.size ?: 0
}
