package com.example.wspoc.gateway

import com.example.wspoc.gateway.relay.RelaySessionRegistry
import com.example.wspoc.support.drain.DrainState
import com.example.wspoc.wscore.testing.TestKeySourceConfig
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GatewayEdgeFilter의 503 검사를 통과한 직후 drain이 시작된 업그레이드. DrainCoordinator의 snapshot에는
 * 없으므로, 게이트웨이가 upstream을 열지 않고 바로 1001로 닫아야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestKeySourceConfig::class, GatewayLateSessionDrainTest.DrainAfterEdgeConfig::class)
@DirtiesContext
class GatewayLateSessionDrainTest(
    @LocalServerPort private val port: Int,
    @Autowired private val relaySessions: RelaySessionRegistry,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class DrainAfterEdgeConfig {
        /** Edge 필터(503 검사)와 WsAuth가 통과시킨 뒤, 업그레이드 전에 drain을 켠다: 경합 구간을 그대로 재현한다. */
        @Bean
        fun drainAfterEdge(drainState: DrainState): GlobalFilter =
            // SCG는 GlobalFilter의 순서를 Ordered로만 읽는다. @Bean 메서드의 @Order는 보지 않아서, 순서 없는 필터는
            // WebsocketRoutingFilter 뒤로 밀려 실행되지 않는다.
            object : GlobalFilter, Ordered {
                override fun getOrder() = 0

                override fun filter(
                    exchange: ServerWebExchange,
                    chain: GatewayFilterChain,
                ): Mono<Void> {
                    if (exchange.request.headers.containsHeader(START_DRAIN)) drainState.startDraining()
                    return chain.filter(exchange)
                }
            }
    }

    companion object {
        private const val START_DRAIN = "X-Test-Start-Drain"

        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
        }
    }

    @Test
    fun `upgrade that passed the drain check just before drain is closed with 1001 without an upstream connection`() =
        runBlocking {
            RelayBackend.seen.clear()
            val headers = HttpHeaders().apply { set(START_DRAIN, "1") }

            val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("late")}"), headers)

            assertEquals(1001, conn.awaitClose(Duration.ofSeconds(3)).code)
            withTimeout(3_000) { while (relaySessions.activeCount > 0) delay(20) }
            assertEquals(emptyList(), RelayBackend.seen, "no upstream connection may be opened while draining")
        }
}
