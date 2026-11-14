package com.example.wspoc.backend

import com.example.wspoc.auth.common.AuthPrincipal
import com.example.wspoc.auth.common.GatewayHeaders
import com.example.wspoc.backend.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=gateway"],
)
class GatewayModeTest(
    @LocalServerPort private val port: Int,
) {
    private val client = WsTestClient()
    private val uri = URI.create("ws://localhost:$port/ws/echo")

    private fun gatewayHeaders(
        userId: String,
        expiresAt: Instant = Instant.now().plusSeconds(900),
    ) = HttpHeaders().apply {
        GatewayHeaders.encode(AuthPrincipal(userId, setOf("ws"), expiresAt)).forEach { (k, v) -> set(k, v) }
    }

    @Test
    fun `trusts identity headers injected by the gateway`() =
        runBlocking {
            val conn = client.connect(uri, gatewayHeaders("relay-user"))

            conn.sendJson("type" to "echo", "id" to "1")

            assertEquals("relay-user", conn.nextJson()["userId"].asString())
            conn.close()
        }

    @Test
    fun `rejects handshakes without gateway headers`() =
        runBlocking {
            val error = assertFailsWith<Exception> { client.connect(uri) }
            assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
        }

    @Test
    fun `closes with 3000 at X-Auth-Exp`() =
        runBlocking {
            val conn = client.connect(uri, gatewayHeaders("u", Instant.now().plusSeconds(2)))

            assertEquals(3000, conn.awaitClose(Duration.ofSeconds(5)).code)
        }
}
