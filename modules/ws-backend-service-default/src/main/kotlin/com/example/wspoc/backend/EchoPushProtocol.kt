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
        if (readOnly) {
            reply(session, error(session, "read only"))
            return true
        }
        when (type) {
            "echo" -> {
                node.put("srvTs", clock.millis())
                node.put("userId", session.principal.userId)
                node.put("server", session.serverId)
                reply(session, node)
            }

            "subscribe" -> {
                val interval = node.path("intervalMs").asLong(push.defaultIntervalMs).coerceIn(push.minIntervalMs, push.maxIntervalMs)
                val bytes = node.path("payloadBytes").asInt(push.defaultPayloadBytes).coerceIn(0, push.maxPayloadBytes)
                startPush(session, node.path("topic").asString(push.defaultTopic), interval, bytes)
            }

            "unsubscribe" -> pushJob?.cancel()

            else -> return false
        }
        return true
    }

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
                val start = System.nanoTime()
                var seq = 0L
                while (isActive) {
                    val message =
                        newNode()
                            .put("type", "push")
                            .put("topic", topic)
                            .put("seq", seq)
                            .put("srvTs", clock.millis())
                            .put("server", session.serverId)
                            .put("payload", payload)
                    // 큐가 가득 차면 버린다(느린 소비자). 버린 수는 `ws.messages.dropped` 메트릭에 집계된다.
                    if (session.trySend(write(message)) == WsSessionContext.Offer.CLOSED) return@launch

                    seq++
                    // 고정 주기 스케줄: 처리 시간 때문에 전송 시각이 밀리지 않게 한다.
                    val nextAt = start + Duration.ofMillis(intervalMs * seq).toNanos()
                    delay(Duration.ofNanos(nextAt - System.nanoTime()).toMillis().coerceAtLeast(0))
                }
            }
    }
}
