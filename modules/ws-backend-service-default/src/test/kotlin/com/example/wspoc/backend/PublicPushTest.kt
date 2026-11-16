package com.example.wspoc.backend

import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `@WsEndpoint(auth = NONE)` 공개 push: 자격증명 없이 연결되고, 읽기 전용이며, 다른 엔드포인트의 인증은 그대로다(D20). */
class PublicPushTest {
    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["ws.auth.mode=gateway", "ws.push.default-interval-ms=50"],
    )
    inner class GatewayMode(
        @LocalServerPort val port: Int,
        @Autowired val registry: SessionRegistry,
    ) {
        private val client = WsTestClient()

        @Test
        fun `anonymous client receives pushes without gateway headers`() =
            runBlocking {
                val conn = client.connect(URI.create("ws://localhost:$port/ws/public/push"))

                val pushes = (1..3).map { conn.nextJson() }

                assertTrue(pushes.all { it["type"].asString() == "push" && it["topic"].asString() == "ticker" })
                assertEquals(listOf(0L, 1L, 2L), pushes.map { it["seq"].asLong() })
                assertTrue(pushes[0]["server"].asString().endsWith(":$port"))
                conn.close()
            }

        @Test
        fun `client messages are rejected as read only`() =
            runBlocking {
                // 위조한 신원 헤더는 익명 엔드포인트에서 쓰이지 않는다.
                val spoofed = HttpHeaders().apply { set(GatewayHeaders.USER_ID, "evil") }
                val conn = client.connect(URI.create("ws://localhost:$port/ws/public/push"), spoofed)

                conn.sendJson("type" to "subscribe", "intervalMs" to 10, "payloadBytes" to 16384)

                val error = generateSequence { runBlocking { conn.nextJson() } }.first { it["type"].asString() == "error" }
                assertEquals("read only", error["reason"].asString())
                conn.close()
            }

        @Test
        fun `authenticated endpoints still require gateway headers`() =
            runBlocking {
                val opened = registry.openedCount
                val error = assertFailsWith<Exception> { client.connect(URI.create("ws://localhost:$port/ws/echo")) }

                assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
                assertEquals(opened, registry.openedCount)
            }
    }

    @Nested
    @SelfModeWsTest
    inner class SelfMode(
        @LocalServerPort val port: Int,
    ) {
        @Test
        fun `anonymous client receives pushes without a token`() =
            runBlocking {
                val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/public/push"))

                assertEquals("push", conn.nextJson()["type"].asString())
                conn.close()
            }
    }
}
