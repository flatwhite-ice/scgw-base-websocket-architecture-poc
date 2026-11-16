package com.example.wspoc.gateway

import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.testing.TestKeySourceConfig
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * fail-closed(D20): 인증이 필요한 backend 엔드포인트로 가는 라우트에 실수로 `WsAuth`를 빠뜨려도 세션은 열리지 않는다.
 * 전역 필터가 신원 헤더를 항상 지우므로 backend(gateway 모드)가 401로 거절한다. 다만 SCG는 클라이언트를 먼저
 * 101로 업그레이드하므로(D14), 클라이언트에는 401이 아니라 "업그레이드 후 즉시 종료"로 보인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestKeySourceConfig::class)
class MissingAuthFilterTest(
    @LocalServerPort private val port: Int,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun routes(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            // 리스트 프로퍼티는 통째로 교체된다: WsAuth 없는 /ws/echo 라우트 하나만 남는다.
            val route = "spring.cloud.gateway.server.webflux.routes[0]"
            registry.add("$route.id") { "echo-without-auth" }
            registry.add("$route.uri") { "ws://localhost:${RelayBackend.port}" }
            registry.add("$route.predicates[0]") { "Path=/ws/echo" }
        }
    }

    @Test
    fun `route without WsAuth cannot open an authenticated backend session even with spoofed headers`() =
        runBlocking {
            RelayBackend.seen.clear()
            val registry = RelayBackend.bean(SessionRegistry::class.java)
            val openedBefore = registry.openedCount
            val spoofed = HttpHeaders().apply { set(GatewayHeaders.USER_ID, "evil") }

            val outcome =
                runCatching {
                    val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo"), spoofed)
                    conn.sendJson("type" to "echo", "id" to "x")
                    conn.awaitClose(Duration.ofSeconds(5))
                }

            // 업그레이드 자체가 실패하거나, 업그레이드 뒤 정상 종료(1000)가 아닌 코드로 닫힌다. echo 응답은 오지 않는다.
            outcome.onSuccess { assertNotEquals(1000, it.code, "session must not be served") }
            // 위조한 X-User-Id가 backend까지 갔다면 gateway 모드 backend는 "evil"로 세션을 열었을 것이다.
            assertEquals(openedBefore, registry.openedCount, "backend must not open a WebSocket session")
            println(
                "route without WsAuth -> client outcome: ${outcome.fold({ "closed ${it.code}" }, { "handshake failed: ${it.message}" })}",
            )
        }
}
