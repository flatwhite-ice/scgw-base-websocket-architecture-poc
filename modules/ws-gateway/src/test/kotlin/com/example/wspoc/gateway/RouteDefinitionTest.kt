package com.example.wspoc.gateway

import com.example.wspoc.wscore.testing.TestKeySourceConfig
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import kotlin.test.assertEquals

/**
 * application.yml / application-static.yml의 라우트 정의가 서비스별로 의도한 대상에 바인딩되는지,
 * 그리고 인증 라우트에만 `WsAuth` 필터가 붙는지 확인한다(D18, D20).
 */
class RouteDefinitionTest {
    @Nested
    @SpringBootTest(properties = ["ws.gateway.auth.jwks-uri="])
    @Import(TestKeySourceConfig::class)
    inner class Discovery(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `each service is reached through the load balancer`() {
            assertEquals(
                mapOf(
                    "ws-backend-public" to URI.create("lb:ws://ws-backend"),
                    "ws-backend" to URI.create("lb:ws://ws-backend"),
                    "ws-chat" to URI.create("lb:ws://ws-chat"),
                ),
                routes.all().mapValues { it.value.uri },
            )
        }

        @Test
        fun `only authenticated routes carry the WsAuth filter`() {
            assertEquals(EXPECTED_AUTH, routes.all().mapValues { it.value.hasWsAuth() })
        }
    }

    @Nested
    @SpringBootTest(
        properties = ["ws.gateway.auth.jwks-uri=", "WS_BACKEND_URI=ws://backend-vip:8090", "WS_CHAT_URI=ws://chat-vip:8091"],
    )
    @Import(TestKeySourceConfig::class)
    @ActiveProfiles("static")
    inner class Static(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `static profile routes each service to a fixed uri`() {
            assertEquals(
                mapOf(
                    "ws-backend-public" to URI.create("ws://backend-vip:8090"),
                    "ws-backend" to URI.create("ws://backend-vip:8090"),
                    "ws-chat" to URI.create("ws://chat-vip:8091"),
                ),
                routes.all().mapValues { it.value.uri },
            )
        }

        @Test
        fun `static profile keeps the same WsAuth declarations`() {
            assertEquals(EXPECTED_AUTH, routes.all().mapValues { it.value.hasWsAuth() })
        }
    }

    private companion object {
        val EXPECTED_AUTH = mapOf("ws-backend-public" to false, "ws-backend" to true, "ws-chat" to true)
    }
}

// 테스트 스레드(논블로킹 스레드 아님)에서 실행되므로 block()을 써도 BlockHound에 걸리지 않는다.
private fun RouteLocator.all(): Map<String, Route> =
    routes
        .collectList()
        .block()!!
        .associateBy { it.id }

/** WsAuth 필터는 [WsAuthGatewayFilterFactory.ORDER]로 고정된 OrderedGatewayFilter로 바인딩된다. */
private fun Route.hasWsAuth(): Boolean = filters.any { (it as? OrderedGatewayFilter)?.order == WsAuthGatewayFilterFactory.ORDER }
