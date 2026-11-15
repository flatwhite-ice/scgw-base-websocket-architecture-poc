package com.example.wspoc.backend

import com.example.wspoc.wscore.session.JsonWsProtocol
import com.example.wspoc.wscore.session.WsSessionContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration

/**
 * echo/push 서비스 프로토콜(JSON 텍스트 프레임). 세션마다 하나씩 만들어진다.
 *  - `{"type":"echo",...}`      -> 같은 객체 + `srvTs`, `userId`(인증된 principal), `server`(이 인스턴스)
 *  - `{"type":"subscribe","topic":"ticker","intervalMs":1000,"payloadBytes":256}` -> 주기적 `push` 메시지
 *  - `{"type":"unsubscribe"}`  -> push 중지
 *
 * [readOnly]이면(익명 공개 엔드포인트) 기본 토픽만 고정 주기로 보내고, 클라이언트 메시지는 모두
 * `{"type":"error","reason":"read only"}`로 거절한다. 익명 사용자가 주기나 payload를 키워 부하를 만들지 못하게 한다.
 */
class EchoPushProtocol(
    private val push: PushProperties,
    jsonMapper: JsonMapper,
    /** `/ws/push`는 연결 즉시 기본 구독을 시작하고, `/ws/echo`는 명시적인 subscribe를 기다린다. */
    private val autoSubscribe: Boolean,
    private val readOnly: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : JsonWsProtocol(jsonMapper) {
    private var pushJob: Job? = null

    override suspend fun onOpen(session: WsSessionContext) {
        if (autoSubscribe) startPush(session, push.defaultTopic, push.defaultIntervalMs, push.defaultPayloadBytes)
    }

    override suspend fun onMessage(
        session: WsSessionContext,
        type: String,
        node: ObjectNode,
    ): Boolean {
        when {
            readOnly -> reply(session, error(session, "read only"))
            type == "echo" -> echo(session, node)
            type == "subscribe" -> subscribe(session, node)
            type == "unsubscribe" -> pushJob?.cancel()
            else -> return false
        }
        return true
    }

    /** 받은 객체에 서버 수신 시각과 처리한 쪽의 정보를 붙여 그대로 돌려준다. */
    private suspend fun echo(
        session: WsSessionContext,
        node: ObjectNode,
    ) {
        node.put("srvTs", clock.millis())
        node.put("userId", session.principal.userId)
        node.put("server", session.serverId)
        reply(session, node)
    }

    /** 요청한 주기와 크기는 설정한 범위 안으로 맞춘다. 빠진 항목은 기본값을 쓴다. */
    private fun subscribe(
        session: WsSessionContext,
        node: ObjectNode,
    ) {
        val topic = node.path("topic").asString(push.defaultTopic)
        val intervalMs = node.path("intervalMs").asLong(push.defaultIntervalMs).coerceIn(push.minIntervalMs, push.maxIntervalMs)
        val payloadBytes = node.path("payloadBytes").asInt(push.defaultPayloadBytes).coerceIn(0, push.maxPayloadBytes)
        startPush(session, topic, intervalMs, payloadBytes)
    }

    /** 이전 구독을 멈추고 새 push 루프를 세션 scope에 띄운다(세션이 끝나면 함께 취소된다). */
    private fun startPush(
        session: WsSessionContext,
        topic: String,
        intervalMs: Long,
        payloadBytes: Int,
    ) {
        pushJob?.cancel()
        pushJob =
            session.scope.launch {
                val payload = "x".repeat(payloadBytes)
                val startedAt = System.nanoTime()
                var seq = 0L
                while (isActive) {
                    // 큐가 가득 차면 버린다(느린 소비자). 버린 수는 `ws.messages.dropped` 메트릭에 집계된다.
                    if (session.trySend(pushMessage(session, topic, seq, payload)) == WsSessionContext.Offer.CLOSED) return@launch
                    seq++
                    // 고정 주기 스케줄: 처리 시간 때문에 전송 시각이 밀리지 않게 한다.
                    val nextAt = startedAt + Duration.ofMillis(intervalMs * seq).toNanos()
                    delay(Duration.ofNanos(nextAt - System.nanoTime()).toMillis().coerceAtLeast(0))
                }
            }
    }

    private fun pushMessage(
        session: WsSessionContext,
        topic: String,
        seq: Long,
        payload: String,
    ): String =
        write(
            newNode()
                .put("type", "push")
                .put("topic", topic)
                .put("seq", seq)
                .put("srvTs", clock.millis())
                .put("server", session.serverId)
                .put("payload", payload),
        )
}
