package com.example.wspoc.backend

import com.example.wspoc.backend.testing.TestKeys
import com.example.wspoc.backend.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri=", "ws.instance-id=backend-a"],
)
@Import(TestAuthConfig::class)
class ServerIdentityTest(
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `ws instance-id overrides the server field`() =
        runBlocking {
            val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("id")}"))

            conn.sendJson("type" to "echo", "id" to "1")

            assertEquals("backend-a", conn.nextJson()["server"].asString())
            conn.close()
        }
}
