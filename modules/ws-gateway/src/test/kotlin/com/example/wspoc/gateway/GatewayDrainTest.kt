package com.example.wspoc.gateway

import com.example.wspoc.gateway.drain.GatewayDrainCoordinator
import com.example.wspoc.gateway.drain.RelaySessionRegistry
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 게이트웨이 종료: relay 세션이 drain window 안에 1001로 닫히고, 그 close가 backend까지 전달된다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.gateway.drain.window=500ms"],
)
@Import(GatewayE2ETest.TestAuthConfig::class)
@DirtiesContext
class GatewayDrainTest(
    @LocalServerPort private val port: Int,
    @Autowired private val drain: GatewayDrainCoordinator,
    @Autowired private val relaySessions: RelaySessionRegistry,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
        }
    }

    private suspend fun uri() = URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("drain")}")

    @Test
    fun `gateway drain closes relayed sessions with 1001 and the backend sessions follow`() =
        runBlocking {
            val backendSessions = RelayBackend.bean(SessionRegistry::class.java)
            val backendBefore = backendSessions.activeCount
            val client = WsTestClient()
            val sessions =
                (1..3).map { n ->
                    client.connect(uri()).also { conn ->
                        // echo 왕복으로 backend까지 relay가 완전히 성립했음을 확인한다.
                        conn.sendJson("type" to "echo", "id" to "$n")
                        assertEquals("$n", conn.nextJson()["id"].asString())
                    }
                }
            assertEquals(3, relaySessions.activeCount)
            assertEquals(backendBefore + 3, backendSessions.activeCount)

            val done = CompletableDeferred<Unit>()
            drain.stop { done.complete(Unit) }

            val rejected = assertFailsWith<Exception> { client.connect(uri()) }
            assertTrue(rejected.message.orEmpty().contains("503"), "unexpected error: $rejected")
            sessions.forEach { assertEquals(1001, it.awaitClose(Duration.ofSeconds(2)).code) }
            withTimeout(3_000) { done.await() }
            assertEquals(0, relaySessions.activeCount)
            withTimeout(3_000) { while (backendSessions.activeCount > backendBefore) delay(50) }
        }
}
