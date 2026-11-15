package com.example.wspoc.backend

import com.example.wspoc.wscore.session.DrainState
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.server.WebFilter
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 핸드셰이크 필터의 503 검사를 통과한 직후 drain이 시작된 세션. DrainCoordinator의 snapshot에는 없으므로
 * 세션이 스스로 1001로 닫아야 한다. 그러지 않으면 서버가 내려갈 때 close 프레임 없이(1006) 끊긴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri="],
)
@Import(TestAuthConfig::class, LateSessionDrainTest.DrainAfterAuthConfig::class)
@DirtiesContext
class LateSessionDrainTest(
    @LocalServerPort private val port: Int,
    @Autowired private val registry: SessionRegistry,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class DrainAfterAuthConfig {
        /** 인증 필터(HIGHEST_PRECEDENCE)가 통과시킨 뒤, 업그레이드 전에 drain을 켠다: 경합 구간을 그대로 재현한다. */
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        fun drainAfterAuth(drainState: DrainState) =
            WebFilter { exchange, chain ->
                if (exchange.request.headers.containsHeader(START_DRAIN)) drainState.startDraining()
                chain.filter(exchange)
            }
    }

    @Test
    fun `session that passed the handshake check just before drain is closed with 1001`() =
        runBlocking {
            val headers = HttpHeaders().apply { set(START_DRAIN, "1") }
            val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("late")}"), headers)

            assertEquals(1001, conn.awaitClose(Duration.ofSeconds(3)).code)
            withTimeout(3_000) { while (registry.activeCount > 0) delay(20) }
        }

    private companion object {
        const val START_DRAIN = "X-Test-Start-Drain"
    }
}
