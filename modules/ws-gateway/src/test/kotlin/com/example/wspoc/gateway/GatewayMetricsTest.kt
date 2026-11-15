package com.example.wspoc.gateway

import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 게이트웨이 메트릭(D27). ws-backend는 실제 서비스로, ws-chat은 아무도 듣지 않는 포트로 두어
 * upstream 연결 실패("101 뒤 1002")가 어떻게 집계되는지도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayMetricsTest.TestAuthConfig::class)
class GatewayMetricsTest(
    @LocalServerPort private val port: Int,
    @Autowired private val registry: MeterRegistry,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class TestAuthConfig {
        @Bean
        fun testKeySource(): KeySource = TestKeys.keySource
    }

    companion object {
        /** 열었다 닫은 포트: 연결하면 바로 거절된다. */
        private val deadPort = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-chat[0].uri") { "http://localhost:$deadPort" }
        }
    }

    private val client = WsTestClient()

    private fun gw(
        path: String,
        query: String = "",
    ) = URI.create("ws://localhost:$port$path$query")

    private fun counter(
        name: String,
        vararg tags: String,
    ) = registry
        .find(name)
        .tags(*tags)
        .counter()
        ?.count() ?: 0.0

    private fun timerCount(
        name: String,
        vararg tags: String,
    ) = registry
        .find(name)
        .tags(*tags)
        .timer()
        ?.count() ?: 0L

    private fun active(route: String) =
        registry
            .find(WsMeters.SESSIONS_ACTIVE)
            .tag("route", route)
            .gauge()
            ?.value() ?: 0.0

    /** 이 라우트에서 끝난 세션 수(close 코드 무관). */
    private fun closes(route: String) =
        registry
            .find(WsMeters.CLOSE)
            .tag("route", route)
            .counters()
            .sumOf { it.count() }

    private suspend fun eventually(check: () -> Boolean) =
        withTimeout(5_000) {
            while (!check()) delay(20)
        }

    @Test
    fun `a relayed session is measured on its route`() =
        runBlocking {
            val route = arrayOf("route", "ws-backend")
            val inBefore = counter(WsMeters.MESSAGES, *route, "direction", "in")
            val outBefore = counter(WsMeters.MESSAGES, *route, "direction", "out")
            val okBefore = timerCount(WsMeters.HANDSHAKE, *route, "result", "ok")
            val connectBefore = timerCount(WsMeters.UPSTREAM_CONNECT, *route, "result", "ok")
            val closedBefore = closes("ws-backend")
            val durationsBefore = timerCount(WsMeters.SESSION_DURATION, *route)

            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("alice")}"))
            conn.sendJson("type" to "echo", "id" to "m1")
            conn.nextJson()
            conn.sendJson("type" to "echo", "id" to "m2")
            conn.nextJson()

            assertEquals(1.0, active("ws-backend"))
            assertEquals(okBefore + 1, timerCount(WsMeters.HANDSHAKE, *route, "result", "ok"))
            assertEquals(connectBefore + 1, timerCount(WsMeters.UPSTREAM_CONNECT, *route, "result", "ok"))
            assertEquals(inBefore + 2, counter(WsMeters.MESSAGES, *route, "direction", "in"))
            assertEquals(outBefore + 2, counter(WsMeters.MESSAGES, *route, "direction", "out"))

            conn.close()
            // 테스트 클라이언트는 close 프레임 없이 끊을 수 있어서 코드는 따지지 않고 종료 건수만 본다.
            eventually { closes("ws-backend") == closedBefore + 1 }
            assertEquals(0.0, active("ws-backend"))
            assertEquals(durationsBefore + 1, timerCount(WsMeters.SESSION_DURATION, *route))
        }

    @Test
    fun `a rejected handshake is counted by reason and opens nothing`() =
        runBlocking {
            val unauthorized = arrayOf("route", "ws-backend", "result", "unauthorized")
            val missingBefore = timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "missing")
            val malformedBefore = timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "malformed")
            val connectsBefore =
                registry
                    .find(WsMeters.UPSTREAM_CONNECT)
                    .tag("route", "ws-backend")
                    .timers()
                    .sumOf { it.count() }

            assertFailsWith<Exception> { client.connect(gw("/ws/echo")) }
            assertFailsWith<Exception> { client.connect(gw("/ws/echo", "?ticket=garbage")) }

            assertEquals(missingBefore + 1, timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "missing"))
            assertEquals(malformedBefore + 1, timerCount(WsMeters.HANDSHAKE, *unauthorized, "reason", "malformed"))
            assertEquals(
                connectsBefore,
                registry
                    .find(WsMeters.UPSTREAM_CONNECT)
                    .tag("route", "ws-backend")
                    .timers()
                    .sumOf { it.count() },
            )
        }

    @Test
    fun `a dead upstream is a successful handshake followed by an upstream connect error`() =
        runBlocking {
            val route = arrayOf("route", "ws-chat")
            val okBefore = timerCount(WsMeters.HANDSHAKE, *route, "result", "ok")

            // SCG는 101을 먼저 응답하므로 클라이언트 쪽 연결은 성공하고, 곧바로 닫힌다(D14).
            val conn = client.connect(gw("/ws/chat", "?ticket=${TestKeys.ticket("bob")}"))
            conn.awaitClose()

            assertEquals(okBefore + 1, timerCount(WsMeters.HANDSHAKE, *route, "result", "ok"))
            eventually {
                registry
                    .find(WsMeters.UPSTREAM_CONNECT)
                    .tags(*route, "result", "error")
                    .timers()
                    .sumOf { it.count() } == 1L
            }
            val failure =
                registry
                    .find(WsMeters.UPSTREAM_CONNECT)
                    .tags(*route, "result", "error")
                    .timers()
                    .single()
            assertTrue(failure.id.getTag("exception") != "none", "exception tag = ${failure.id.getTag("exception")}")
            eventually { active("ws-chat") == 0.0 }
            assertTrue(
                registry
                    .find(WsMeters.CLOSE)
                    .tag("route", "ws-chat")
                    .counters()
                    .sumOf { it.count() } >= 1.0,
            )
        }

    @Test
    fun `event loop lag is sampled`() =
        runBlocking {
            eventually {
                (
                    registry
                        .find(EventLoopLagProbe.NAME)
                        .tag("pool", "netty")
                        .timer()
                        ?.count() ?: 0L
                ) > 0
            }
        }
}
