package com.example.wspoc.gateway

import com.example.wspoc.auth.common.GatewayHeaders
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.backend.testing.TestKeys
import com.example.wspoc.backend.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Client -> ws-gateway (SCG, JWT check) -> ws-backend (gateway auth mode): the relay architecture end to end. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayE2ETest.TestAuthConfig::class)
class GatewayE2ETest(
    @LocalServerPort private val port: Int,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class TestAuthConfig {
        @Bean
        fun testKeySource(): KeySource = TestKeys.keySource
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
        }
    }

    private val client = WsTestClient()

    private fun gw(
        path: String,
        query: String = "",
    ) = URI.create("ws://localhost:$port$path$query")

    @BeforeEach
    fun reset() = RelayBackend.seen.clear()

    @Test
    fun `echo is relayed to the backend with the authenticated identity`() =
        runBlocking {
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("alice")}&foo=bar"))

            conn.sendJson("type" to "echo", "id" to "e1", "payload" to "via-gateway")
            val reply = conn.nextJson()

            assertEquals("e1", reply["id"].asString())
            assertEquals("via-gateway", reply["payload"].asString())
            assertEquals("alice", reply["userId"].asString())
            // The response names the backend instance that served it, not the gateway.
            assertTrue(reply["server"].asString().endsWith(":${RelayBackend.port}"), "server=${reply["server"]}")
            val seen = RelayBackend.seen.single()
            assertEquals("alice", seen.headers.getFirst(GatewayHeaders.USER_ID))
            assertFalse(
                seen.uri.query
                    .orEmpty()
                    .contains("ticket"),
                "ticket must not be forwarded: ${seen.uri}",
            )
            assertTrue(
                seen.uri.query
                    .orEmpty()
                    .contains("foo=bar"),
                "other query params are kept: ${seen.uri}",
            )
            conn.close()
        }

    @Test
    fun `bearer access token works through the gateway and is not forwarded`() =
        runBlocking {
            val headers = HttpHeaders().apply { setBearerAuth(TestKeys.accessToken("bob")) }
            val conn = client.connect(gw("/ws/echo"), headers)

            conn.sendJson("type" to "echo", "id" to "b1")

            assertEquals("bob", conn.nextJson()["userId"].asString())
            assertEquals(
                null,
                RelayBackend.seen
                    .single()
                    .headers
                    .getFirst(HttpHeaders.AUTHORIZATION),
            )
            conn.close()
        }

    @Test
    fun `unauthenticated upgrade gets 401 and no upstream connection is made`() =
        runBlocking {
            val noCreds = assertFailsWith<Exception> { client.connect(gw("/ws/echo")) }
            val badTicket = assertFailsWith<Exception> { client.connect(gw("/ws/echo", "?ticket=garbage")) }

            assertTrue(noCreds.message.orEmpty().contains("401"), "unexpected error: $noCreds")
            assertTrue(badTicket.message.orEmpty().contains("401"), "unexpected error: $badTicket")
            assertEquals(emptyList(), RelayBackend.seen, "backend must not see rejected handshakes")
        }

    @Test
    fun `spoofed identity headers from the client are replaced`() =
        runBlocking {
            val spoofed =
                HttpHeaders().apply {
                    set(GatewayHeaders.USER_ID, "evil")
                    set(GatewayHeaders.SCOPES, "admin")
                    set(GatewayHeaders.AUTH_EXP, "9999999999")
                }
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("carol")}"), spoofed)

            conn.sendJson("type" to "echo", "id" to "s1")

            assertEquals("carol", conn.nextJson()["userId"].asString())
            val seen = RelayBackend.seen.single().headers
            assertEquals(listOf("carol"), seen[GatewayHeaders.USER_ID])
            assertEquals(listOf("ws"), seen[GatewayHeaders.SCOPES])
            assertTrue(seen.getFirst(GatewayHeaders.AUTH_EXP)!!.toLong() < 9999999999)
            conn.close()
        }

    @Test
    fun `spoofed identity headers alone do not authenticate`() =
        runBlocking {
            val spoofed = HttpHeaders().apply { set(GatewayHeaders.USER_ID, "evil") }

            val error = assertFailsWith<Exception> { client.connect(gw("/ws/echo"), spoofed) }

            assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
            assertEquals(emptyList(), RelayBackend.seen)
        }

    @Test
    fun `ticket cannot be replayed through the gateway`() =
        runBlocking {
            val ticket = TestKeys.ticket("dave")
            client.connect(gw("/ws/echo", "?ticket=$ticket")).close()

            val replay = assertFailsWith<Exception> { client.connect(gw("/ws/echo", "?ticket=$ticket")) }

            assertTrue(replay.message.orEmpty().contains("401"), "unexpected error: $replay")
        }

    @Test
    fun `push messages are relayed`() =
        runBlocking {
            val conn = client.connect(gw("/ws/push", "?ticket=${TestKeys.ticket("erin")}"))

            conn.sendJson("type" to "subscribe", "topic" to "relay", "intervalMs" to 50, "payloadBytes" to 64)
            val pushes =
                generateSequence { runBlocking { conn.nextJson() } }
                    .filter { it["topic"].asString() == "relay" }
                    .take(3)
                    .toList()

            assertEquals(listOf(0L, 1L, 2L), pushes.map { it["seq"].asLong() })
            assertTrue(pushes.all { it["payload"].asString().length == 64 })
            conn.close()
        }

    @Test
    fun `backend close code 4401 on token expiry reaches the client through the gateway`() =
        runBlocking {
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("frank", sessionTtl = Duration.ofSeconds(2))}"))

            assertEquals(4401, conn.awaitClose(Duration.ofSeconds(6)).code)
        }

    @Test
    fun `non-websocket requests are not authenticated by the ws filter`() {
        // Plain HTTP to an unrouted path is just a 404 from the gateway; the filter only guards upgrades.
        val status =
            org.springframework.web.reactive.function.client.WebClient
                .create("http://localhost:$port")
                .get()
                .uri("/nope")
                .exchangeToMono {
                    reactor.core.publisher.Mono
                        .just(it.statusCode().value())
                }.block()
        assertEquals(404, status)
    }
}
