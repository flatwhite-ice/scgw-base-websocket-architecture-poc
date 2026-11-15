package com.example.wspoc.chat

import com.example.wspoc.wscore.session.JsonWsProtocol
import com.example.wspoc.wscore.session.WsSessionContext
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock

/**
 * 방 단위 채팅 프로토콜(JSON 텍스트 프레임). 세션마다 하나씩 만들어진다.
 *  - `{"type":"join","room":"lobby"}`              -> `{"type":"joined","room","userId","members","server"}`
 *  - `{"type":"say","room":"lobby","text":"hi"}`   -> 방의 모든 멤버(보낸 사람 포함)에게
 *                                                     `{"type":"message","room","from","text","srvTs","server"}`
 *  - `{"type":"leave","room":"lobby"}`             -> `{"type":"left","room","server"}`
 * 연결이 끝나면 들어가 있던 방에서 모두 나간다. 오류 응답 형식은 ws-backend와 같다(JsonWsProtocol).
 */
class ChatProtocol(
    private val rooms: ChatRooms,
    jsonMapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) : JsonWsProtocol(jsonMapper) {
    /** 이 세션이 들어가 있는 방. 세션 코루틴에서만 바뀌므로 동기화가 필요 없다. */
    private val joined = mutableSetOf<String>()

    override suspend fun onMessage(
        session: WsSessionContext,
        type: String,
        node: ObjectNode,
    ): Boolean {
        if (type !in TYPES) return false
        val room = node.path("room").asString("")
        when {
            room.isBlank() -> reply(session, error(session, "room required"))
            type == "join" -> join(session, room)
            type == "say" -> say(session, room, node.path("text").asString(""))
            type == "leave" -> leave(session, room)
        }
        return true
    }

    private suspend fun join(
        session: WsSessionContext,
        room: String,
    ) {
        if (joined.add(room)) rooms.join(room, session)
        val joinedReply =
            newNode()
                .put("type", "joined")
                .put("room", room)
                .put("userId", session.principal.userId)
                .put("members", rooms.members(room))
                .put("server", session.serverId)
        reply(session, joinedReply)
    }

    private suspend fun say(
        session: WsSessionContext,
        room: String,
        text: String,
    ) {
        if (room !in joined) return reply(session, error(session, "not joined"))
        val message =
            newNode()
                .put("type", "message")
                .put("room", room)
                .put("from", session.principal.userId)
                .put("text", text)
                .put("srvTs", clock.millis())
                .put("server", session.serverId)
        rooms.broadcast(room, write(message))
    }

    private suspend fun leave(
        session: WsSessionContext,
        room: String,
    ) {
        if (joined.remove(room)) rooms.leave(room, session)
        reply(session, newNode().put("type", "left").put("room", room).put("server", session.serverId))
    }

    override fun onClose(session: WsSessionContext) {
        joined.forEach { rooms.leave(it, session) }
        joined.clear()
    }

    private companion object {
        val TYPES = setOf("join", "say", "leave")
    }
}
