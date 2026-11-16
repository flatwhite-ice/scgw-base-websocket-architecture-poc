package com.example.wspoc.backend

import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 공통 메트릭(D27)이 실제 세션에서 올라가는지 확인한다. 컨텍스트가 다른 테스트와 공유될 수 있으므로 절댓값이 아니라 증가분을 본다.
 */
@SelfModeWsTest
class MetricsTest(
    @LocalServerPort private val port: Int,
    @Autowired private val registry: MeterRegistry,
) {
    private val client = WsTestClient()

    private fun ws(
        path: String,
        query: String = "",
    ) = URI.create("ws://localhost:$port$path$query")

    private fun counter(
        name: String,
        vararg tags: String,
    ) = registry
        .find(name)
        .tags(*tags)
        .counter()
        ?.count() ?: 0.0

    private fun timerCount(
        name: String,
        vararg tags: String,
    ) = registry
        .find(name)
        .tags(*tags)
        .timer()
        ?.count() ?: 0L

    private fun active(endpoint: String) =
        registry
            .get(WsMeters.SESSIONS_ACTIVE)
            .tag("endpoint", endpoint)
            .gauge()
            .value()

    /** 이 endpoint에서 끝난 세션 수(close 코드 무관). */
    private fun closes(endpoint: String) =
        registry
            .find(WsMeters.CLOSE)
            .tag("endpoint", endpoint)
            .counters()
            .sumOf { it.count() }

    /** 종료 쪽 메트릭은 세션 코루틴이 끝난 뒤에 기록되므로 잠시 기다린다. */
    private suspend fun eventually(check: () -> Boolean) =
        withTimeout(5_000) {
            while (!check()) delay(20)
        }

    @Test
    fun `every endpoint has an active gauge before any connection`() {
        listOf("/ws/echo", "/ws/push", "/ws/public/push").forEach { assertTrue(active(it) >= 0.0) }
    }

    @Test
    fun `a session moves the active gauge, message counters, close counter and duration`() =
        runBlocking {
            val echo = arrayOf("endpoint", "/ws/echo")
            val activeBefore = active("/ws/echo")
            val inBefore = counter(WsMeters.MESSAGES, *echo, "direction", "in")
            val outBefore = counter(WsMeters.MESSAGES, *echo, "direction", "out")
            val okBefore = timerCount(WsMeters.HANDSHAKE, *echo, "result", "ok")
            val closedBefore = closes("/ws/echo")
            val durationsBefore = timerCount(WsMeters.SESSION_DURATION, *echo)

            val conn = client.connect(ws("/ws/echo", "?ticket=${TestKeys.ticket("alice")}"))
            conn.sendJson("type" to "echo", "id" to "m1")
            conn.nextJson()
            conn.sendJson("type" to "echo", "id" to "m2")
            conn.nextJson()

            assertEquals(activeBefore + 1, active("/ws/echo"))
            assertEquals(okBefore + 1, timerCount(WsMeters.HANDSHAKE, *echo, "result", "ok"))
            assertEquals(inBefore + 2, counter(WsMeters.MESSAGES, *echo, "direction", "in"))
            assertEquals(outBefore + 2, counter(WsMeters.MESSAGES, *echo, "direction", "out"))

            conn.close()
            // 테스트 클라이언트는 close 프레임 없이 끊을 수 있어서 코드는 따지지 않고 종료 건수만 본다.
            eventually { closes("/ws/echo") == closedBefore + 1 }
            assertEquals(activeBefore, active("/ws/echo"))
            assertEquals(durationsBefore + 1, timerCount(WsMeters.SESSION_DURATION, *echo))
        }

    @Test
    fun `binary frames are counted as received data frames like the gateway does`() =
        runBlocking {
            val inbound = arrayOf("endpoint", "/ws/echo", "direction", "in")
            val inBefore = counter(WsMeters.MESSAGES, *inbound)

            // 프로토콜은 BINARY를 무시하므로 응답이 없다. 뒤의 TEXT echo 응답으로 두 프레임이 모두 처리된 것을 확인한다.
            ReactorNettyWebSocketClient()
                .execute(ws("/ws/echo", "?ticket=${TestKeys.ticket("alice")}")) { session ->
                    val frames =
                        Flux.just(
                            session.binaryMessage { it.wrap(byteArrayOf(1, 2, 3)) },
                            session.textMessage("""{"type":"echo","id":"after-binary"}"""),
                        )
                    session.send(frames).thenMany(session.receive().take(1)).then()
                }.timeout(Duration.ofSeconds(5))
                .awaitSingleOrNull()

            assertEquals(inBefore + 2, counter(WsMeters.MESSAGES, *inbound))
        }

    @Test
    fun `rejected handshakes are counted by reason`() =
        runBlocking {
            val unauthorized = arrayOf("endpoint", "/ws/echo", "result", "unauthorized")
            val missingBefore = timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "missing")
            val malformedBefore = timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "malformed")
            val replayedBefore = timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "replayed")

            assertFailsWith<Exception> { client.connect(ws("/ws/echo")) }
            assertFailsWith<Exception> { client.connect(ws("/ws/echo", "?ticket=garbage")) }
            val ticket = TestKeys.ticket("carol")
            client.connect(ws("/ws/echo", "?ticket=$ticket")).close()
            assertFailsWith<Exception> { client.connect(ws("/ws/echo", "?ticket=$ticket")) }

            assertEquals(missingBefore + 1, timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "missing"))
            assertEquals(malformedBefore + 1, timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "malformed"))
            assertEquals(replayedBefore + 1, timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "replayed"))
        }

    @Test
    fun `an authenticated handshake to a path nobody serves is an error on the unknown endpoint`() =
        runBlocking {
            val tags = arrayOf("endpoint", WsMeters.UNKNOWN_SCOPE, "result", "error")
            val before = timerCount(WsMeters.HANDSHAKE, *tags)

            assertFailsWith<Exception> { client.connect(ws("/ws/nowhere/${System.nanoTime()}", "?ticket=${TestKeys.ticket("dave")}")) }

            eventually { timerCount(WsMeters.HANDSHAKE, *tags) == before + 1 }
            // 요청 경로가 태그 값으로 새지 않는다.
            assertTrue(
                registry.find(WsMeters.HANDSHAKE).timers().none {
                    it.id
                        .getTag("endpoint")
                        .orEmpty()
                        .contains("nowhere")
                },
            )
        }

    @Test
    fun `anonymous endpoint sessions are counted on their own endpoint`() =
        runBlocking {
            val before = active("/ws/public/push")
            val conn = client.connect(ws("/ws/public/push"))

            eventually { active("/ws/public/push") == before + 1 }
            conn.close()
            eventually { active("/ws/public/push") == before }
        }

    @Test
    fun `event loop lag is sampled on netty and on the session scheduler`() =
        runBlocking {
            eventually {
                listOf("netty", "parallel").all { pool ->
                    (
                        registry
                            .find(EventLoopLagProbe.NAME)
                            .tag("pool", pool)
                            .timer()
                            ?.count() ?: 0L
                    ) > 0
                }
            }
        }
}
