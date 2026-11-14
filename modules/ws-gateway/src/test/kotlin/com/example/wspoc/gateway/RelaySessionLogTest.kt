package com.example.wspoc.gateway

import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 게이트웨이 relay 세션 로그(`ws.session`, D21): 라우트, 사용자, 고른 backend, 종료 코드, active 수. 티켓은 남기지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayE2ETest.TestAuthConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class RelaySessionLogTest(
    @LocalServerPort private val port: Int,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
        }
    }

    @Test
    fun `relay open and close are logged with route, user, upstream and active count`(output: CapturedOutput) =
        runBlocking {
            val ticket = TestKeys.ticket("relay-log")
            val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=$ticket"))
            conn.sendJson("type" to "echo", "id" to "1")
            conn.nextJson()
            val upstream = "localhost:${RelayBackend.port}"
            val opened =
                output.awaitLine(
                    Regex(
                        "ws relay opened id=(\\S+) route=ws-backend path=/ws/echo user=relay-log upstream=$upstream remote=\\S+ active=(\\d+)",
                    ),
                )
            assertTrue(opened.groupValues[2].toInt() >= 1)

            conn.close()

            output.awaitLine(
                Regex(
                    "ws relay closed id=${opened.groupValues[1]} route=ws-backend path=/ws/echo user=relay-log upstream=$upstream code=\\d+ .*durationMs=\\d+ active=\\d+",
                ),
            )
            assertFalse(output.out.contains(ticket), "ticket leaked into logs")
        }

    @Test
    fun `anonymous route is logged without a user and without the query string`(output: CapturedOutput) =
        runBlocking {
            // 익명 라우트에는 WsAuth가 없어서 쿼리가 그대로 backend까지 간다. 로그에는 경로만 남아야 한다.
            val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/public/push?ticket=should-not-be-logged"))

            output.awaitLine(Regex("ws relay opened id=\\S+ route=ws-backend-public path=/ws/public/push user=- "))
            conn.close()
            assertFalse(output.out.contains("should-not-be-logged"), "query leaked into logs")
        }
}

private suspend fun CapturedOutput.awaitLine(
    pattern: Regex,
    timeout: Duration = Duration.ofSeconds(3),
): MatchResult {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        pattern.find(out)?.let { return it }
        check(System.nanoTime() < deadline) { "no log line matching $pattern" }
        delay(25)
    }
}
