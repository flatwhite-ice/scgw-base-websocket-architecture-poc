package com.example.wspoc.backend

import com.example.wspoc.wscore.session.DrainCoordinator
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri=", "ws.drain.window=500ms"],
)
@Import(TestAuthConfig::class)
@DirtiesContext
class DrainTest(
    @LocalServerPort private val port: Int,
    @Autowired private val drain: DrainCoordinator,
    @Autowired private val registry: SessionRegistry,
) {
    private suspend fun uri() = URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("drain")}")

    @Test
    fun `drain closes all sessions with 1001 Going Away within the window and rejects new handshakes with 503`() =
        runBlocking {
            val client = WsTestClient()
            val sessions = (1..5).map { client.connect(uri()) }
            assertEquals(5, registry.activeCount)

            val done = CompletableDeferred<Unit>()
            drain.stop { done.complete(Unit) }

            val rejected = assertFailsWith<Exception> { client.connect(uri()) }
            assertTrue(rejected.message.orEmpty().contains("503"), "unexpected error: $rejected")
            sessions.forEach { assertEquals(1001, it.awaitClose(Duration.ofSeconds(2)).code) }
            withTimeout(3_000) { done.await() }
            assertEquals(0, registry.activeCount)
        }
}
