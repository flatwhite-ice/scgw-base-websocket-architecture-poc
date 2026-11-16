package com.example.wspoc.wscore.protocol

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * `{"type": ...}` JSON 텍스트 프레임 프로토콜의 공통 뼈대. 서비스 간에 오류 응답 형식을 맞춘다:
 *  - JSON 객체가 아니면 `{"type":"error","reason":"invalid json","server":...}`
 *  - [onMessage]가 false를 반환하면(모르는 type) `{"type":"error","reason":"unknown type","server":...}`
 * 어느 경우에도 연결은 닫지 않는다.
 */
abstract class JsonWsProtocol(
    protected val jsonMapper: JsonMapper,
) : WsSessionProtocol {
    final override suspend fun onText(
        session: WsSessionContext,
        text: String,
    ) {
        val node =
            try {
                jsonMapper.readTree(text) as? ObjectNode
            } catch (_: JacksonException) {
                null
            } ?: return reply(session, error(session, "invalid json"))

        // 기본값을 준다: 인자 없는 asString()은 `type`이 객체나 배열이면 예외를 던진다. 그런 값은 모르는 type으로 응답한다.
        if (!onMessage(session, node.path("type").asString(""), node)) reply(session, error(session, "unknown type"))
    }

    /** 메시지 하나를 처리한다. 모르는 [type]이면 false. */
    protected abstract suspend fun onMessage(
        session: WsSessionContext,
        type: String,
        node: ObjectNode,
    ): Boolean

    protected fun newNode(): ObjectNode = jsonMapper.createObjectNode()

    protected fun write(node: ObjectNode): String = jsonMapper.writeValueAsString(node)

    protected suspend fun reply(
        session: WsSessionContext,
        node: ObjectNode,
    ) = session.send(write(node))

    protected fun error(
        session: WsSessionContext,
        reason: String,
    ): ObjectNode = newNode().put("type", "error").put("reason", reason).put("server", session.serverId)
}
