package com.example.wspoc.backend

import com.example.wspoc.backend.session.SessionRegistry
import com.example.wspoc.backend.testing.TestKeys
import com.example.wspoc.backend.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri="],
)
@org.springframework.context.annotation.Import(TestAuthConfig::class)
class SelfModeTest(
    @LocalServerPort private val port: Int,
    @Autowired private val registry: SessionRegistry,
) {
    private val client = WsTestClient()

    private fun ws(
        path: String,
        query: String = "",
    ) = URI.create("ws://localhost:$port$path$query")

    @Test
    fun `echo round trip with ticket adds srvTs and principal userId`() =
        runBlocking {
            val conn = client.connect(ws("/ws/echo", "?ticket=${TestKeys.ticket("alice")}"))

            conn.sendJson("type" to "echo", "id" to "m1", "ts" to 123L, "payload" to "hello")
            val reply = conn.nextJson()

            assertEquals("echo", reply["type"].asString())
            assertEquals("m1", reply["id"].asString())
            assertEquals(123L, reply["ts"].asLong())
            assertEquals("hello", reply["payload"].asString())
            assertEquals("alice", reply["userId"].asString())
            assertTrue(reply["srvTs"].asLong() > 0)
            assertTrue(reply["server"].asString().matches(Regex("ws-backend@.+:$port")), "server=${reply["server"]}")
            conn.close()
        }

    @Test
    fun `bearer access token is accepted`() =
        runBlocking {
            val headers = HttpHeaders().apply { setBearerAuth(TestKeys.accessToken("bob")) }
            val conn = client.connect(ws("/ws/echo"), headers)

            conn.sendJson("type" to "echo", "id" to "m1")

            assertEquals("bob", conn.nextJson()["userId"].asString())
            conn.close()
        }

    @Test
    fun `missing credentials are rejected with HTTP 401 before the upgrade`() =
        runBlocking {
            val openedBefore = registry.openedCount

            // HTTP 관점: 핸드셰이크가 101 Switching Protocols가 아니라 401 응답을 받는다.
            val status =
                assertFailsWith<WebClientResponseException> {
                    WebClient
                        .create("http://localhost:$port")
                        .get()
                        .uri("/ws/echo")
                        .header(HttpHeaders.CONNECTION, "Upgrade")
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header("Sec-WebSocket-Version", "13")
                        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                        .retrieve()
                        .toBodilessEntity()
                        .block()
                }.statusCode.value()
            assertEquals(401, status)

            // WebSocket 클라이언트 관점: 핸드셰이크가 실패한다.
            val error = assertFailsWith<Exception> { client.connect(ws("/ws/echo")) }
            assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
            assertEquals(openedBefore, registry.openedCount, "no WebSocket session may be created")
        }

    @Test
    fun `invalid and reused tickets are rejected`() =
        runBlocking {
            assertFailsWith<Exception> { client.connect(ws("/ws/echo", "?ticket=garbage")) }

            val ticket = TestKeys.ticket("carol")
            client.connect(ws("/ws/echo", "?ticket=$ticket")).close()
            val reuse = assertFailsWith<Exception> { client.connect(ws("/ws/echo", "?ticket=$ticket")) }
            assertTrue(reuse.message.orEmpty().contains("401"), "unexpected error: $reuse")
        }

    @Test
    fun `access token cannot be passed as ticket`() =
        runBlocking {
            assertFailsWith<Exception> { client.connect(ws("/ws/echo", "?ticket=${TestKeys.accessToken("dave")}")) }
            Unit
        }

    @Test
    fun `push endpoint streams periodic messages and honours subscribe parameters`() =
        runBlocking {
            val conn = client.connect(ws("/ws/push", "?ticket=${TestKeys.ticket("erin")}"))

            // 기본 구독은 연결 즉시 시작된다.
            val first = conn.nextJson()
            assertEquals("push", first["type"].asString())
            assertEquals("ticker", first["topic"].asString())
            assertEquals(256, first["payload"].asString().length)
            assertTrue(first["server"].asString().endsWith(":$port"), "server=${first["server"]}")

            conn.sendJson("type" to "subscribe", "topic" to "fast", "intervalMs" to 50, "payloadBytes" to 32)
            val fast = generateSequence { runBlocking { conn.nextJson() } }.first { it["topic"].asString() == "fast" }
            val next = (1..3).map { conn.nextJson(Duration.ofSeconds(1)) }
            val seqs = listOf(fast, *next.toTypedArray()).map { it["seq"].asLong() }

            assertEquals(listOf(0L, 1L, 2L, 3L), seqs)
            assertTrue(next.all { it["payload"].asString().length == 32 && it["topic"].asString() == "fast" })
            conn.close()
        }

    @Test
    fun `session is closed with 3000 Unauthorized when the token expires`() =
        runBlocking {
            val headers = HttpHeaders().apply { setBearerAuth(TestKeys.accessToken("frank", Duration.ofSeconds(2))) }
            val conn = client.connect(ws("/ws/echo"), headers)

            val status = conn.awaitClose(Duration.ofSeconds(5))

            assertEquals(3000, status.code)
        }

    @Test
    fun `ticket sessions expire with the bound access token, not the ticket`() =
        runBlocking {
            val conn = client.connect(ws("/ws/echo", "?ticket=${TestKeys.ticket("gina", sessionTtl = Duration.ofSeconds(2))}"))

            assertEquals(3000, conn.awaitClose(Duration.ofSeconds(5)).code)
        }

    @Test
    fun `malformed and unknown messages get an error reply without closing`() =
        runBlocking {
            val conn = client.connect(ws("/ws/echo", "?ticket=${TestKeys.ticket("hank")}"))

            conn.send("{not json")
            val invalid = conn.nextJson()
            assertEquals("invalid json", invalid["reason"].asString())
            assertTrue(invalid["server"].asString().endsWith(":$port"))
            conn.sendJson("type" to "nope")
            assertEquals("unknown type", conn.nextJson()["reason"].asString())
            conn.sendJson("type" to "echo", "id" to "still-open")
            assertEquals("still-open", conn.nextJson()["id"].asString())
            conn.close()
        }
}
