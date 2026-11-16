package com.example.wspoc.backend

import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

@SelfModeWsTest
@TestPropertySource(properties = ["ws.instance-id=backend-a"])
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
