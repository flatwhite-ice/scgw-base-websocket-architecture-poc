package com.example.wspoc.backend

import com.example.wspoc.backend.testing.TestKeys
import com.example.wspoc.backend.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.heartbeat.interval=200ms", "ws.heartbeat.max-missed=2"],
)
@Import(TestAuthConfig::class)
class HeartbeatTest(
    @LocalServerPort private val port: Int,
) {
    private suspend fun uri() = URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("hb")}")

    @Test
    fun `client answering pings stays connected`() =
        runBlocking {
            val conn = WsTestClient(answerPings = true).connect(uri())

            delay(1_500) // ~7 heartbeat intervals

            assertFalse(conn.closeStatus.isCompleted, "session should still be open")
            conn.sendJson("type" to "echo", "id" to "alive")
            assertEquals("alive", conn.nextJson()["id"].asString())
            conn.close()
        }

    @Test
    fun `client not answering pings is closed with 4408 after two missed pongs`() =
        runBlocking {
            val conn = WsTestClient(answerPings = false).connect(uri())

            val status = conn.awaitClose(Duration.ofSeconds(3))

            assertEquals(4408, status.code)
            assertTrue(conn.pingsReceived.get() >= 2, "expected pings, got ${conn.pingsReceived.get()}")
        }
}
