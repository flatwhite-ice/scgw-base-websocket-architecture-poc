package com.example.wspoc.backend

import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SelfModeWsTest
@TestPropertySource(properties = ["ws.heartbeat.interval=200ms", "ws.heartbeat.max-missed=2"])
class HeartbeatTest(
    @LocalServerPort private val port: Int,
) {
    private suspend fun uri() = URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("hb")}")

    @Test
    fun `client answering pings stays connected`() =
        runBlocking {
            val conn = WsTestClient(answerPings = true).connect(uri())

            delay(1_500) // heartbeat 주기 약 7회

            assertFalse(conn.closeStatus.isCompleted, "session should still be open")
            conn.sendJson("type" to "echo", "id" to "alive")
            assertEquals("alive", conn.nextJson()["id"].asString())
            conn.close()
        }

    @Test
    fun `client not answering pings is closed with 3008 Timeout after two missed pongs`() =
        runBlocking {
            val conn = WsTestClient(answerPings = false).connect(uri())

            val status = conn.awaitClose(Duration.ofSeconds(3))

            assertEquals(3008, status.code)
            assertTrue(conn.pingsReceived.get() >= 2, "expected pings, got ${conn.pingsReceived.get()}")
        }
}
