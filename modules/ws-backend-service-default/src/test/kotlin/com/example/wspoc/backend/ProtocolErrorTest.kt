package com.example.wspoc.backend

import com.example.wspoc.wscore.endpoint.WsEndpoint
import com.example.wspoc.wscore.session.ManagedWsHandler
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.session.WsSessionContext
import com.example.wspoc.wscore.session.WsSessionProtocol
import com.example.wspoc.wscore.session.WsSessionRuntime
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 프로토콜 코드가 던진 예외와 비정상 입력 처리.
 *  - 프로토콜 예외는 코드 없는 종료(1005)가 아니라 1011로 닫힌다. 다른 세션에는 영향이 없다.
 *  - 문자열이 아닌 `type`, 지나치게 큰 `intervalMs`는 세션을 죽이지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri="],
)
@Import(TestAuthConfig::class, ProtocolErrorTest.BoomConfig::class)
class ProtocolErrorTest(
    @LocalServerPort private val port: Int,
    @Autowired private val registry: SessionRegistry,
) {
    /** `throw`면 콜백에서, `spawn`이면 세션 scope에 띄운 코루틴에서 예외를 던지는 프로토콜. 그 밖에는 그대로 돌려준다. */
    class BoomProtocol : WsSessionProtocol {
        override suspend fun onText(
            session: WsSessionContext,
            text: String,
        ) {
            when (text) {
                "throw" -> error("boom in onText")
                "spawn" -> session.scope.launch { error("boom in child coroutine") }
                else -> session.send(text)
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class BoomConfig {
        @Bean
        @WsEndpoint("/ws/test/boom")
        fun boomHandler(runtime: WsSessionRuntime) = ManagedWsHandler(runtime) { BoomProtocol() }
    }

    private val client = WsTestClient()

    private suspend fun connect(path: String) = client.connect(URI.create("ws://localhost:$port$path?ticket=${TestKeys.ticket("boom")}"))

    private suspend fun awaitNoSessions() = withTimeout(3_000) { while (registry.activeCount > 0) delay(20) }

    @Test
    fun `exception thrown by the protocol closes only that session with 1011`() =
        runBlocking {
            val healthy = connect("/ws/test/boom")
            val failing = connect("/ws/test/boom")

            failing.send("throw")

            assertEquals(1011, failing.awaitClose().code)
            healthy.send("still-here")
            assertEquals("still-here", withTimeout(2_000) { healthy.received.receive() })
            healthy.close()
            awaitNoSessions()
        }

    @Test
    fun `exception in a coroutine launched in the session scope closes the session with 1011`() =
        runBlocking {
            val conn = connect("/ws/test/boom")

            conn.send("spawn")

            assertEquals(1011, conn.awaitClose().code)
            awaitNoSessions()
        }

    @Test
    fun `non-string type gets an error reply and keeps the session open`() =
        runBlocking {
            val conn = connect("/ws/echo")

            for (message in listOf("""{"type":{}}""", """{"type":[1]}""")) {
                conn.send(message)
                assertEquals("unknown type", conn.nextJson()["reason"].asString(), message)
            }
            conn.sendJson("type" to "echo", "id" to "still-open")
            assertEquals("still-open", conn.nextJson()["id"].asString())
            conn.close()
        }

    @Test
    fun `oversized subscribe interval is clamped instead of killing the session`() =
        runBlocking {
            val conn = connect("/ws/echo")

            conn.send("""{"type":"subscribe","intervalMs":10000000000000}""")
            assertEquals("push", conn.nextJson()["type"].asString())
            // 예전에는 첫 push 직후 다음 전송 시각 계산이 overflow해서 세션이 끊겼다.
            delay(300)
            conn.sendJson("type" to "echo", "id" to "still-open")

            assertEquals("still-open", conn.nextJson(Duration.ofSeconds(2))["id"].asString())
            conn.close()
        }
}
