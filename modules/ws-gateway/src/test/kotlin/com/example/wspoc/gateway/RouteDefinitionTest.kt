package com.example.wspoc.gateway

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import kotlin.test.assertEquals

/** application.yml / application-static.yml의 라우트 정의가 서비스별로 의도한 대상에 바인딩되는지 확인한다. */
class RouteDefinitionTest {
    @Nested
    @SpringBootTest(properties = ["ws.gateway.auth.jwks-uri="])
    @Import(WsGatewayApplicationTests.Keys::class)
    inner class Discovery(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `each service is reached through the load balancer`() {
            assertEquals(
                mapOf("ws-backend" to URI.create("lb:ws://ws-backend"), "ws-chat" to URI.create("lb:ws://ws-chat")),
                routes.uris(),
            )
        }
    }

    @Nested
    @SpringBootTest(
        properties = ["ws.gateway.auth.jwks-uri=", "WS_BACKEND_URI=ws://backend-vip:8090", "WS_CHAT_URI=ws://chat-vip:8091"],
    )
    @Import(WsGatewayApplicationTests.Keys::class)
    @ActiveProfiles("static")
    inner class Static(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `static profile routes each service to a fixed uri`() {
            assertEquals(
                mapOf("ws-backend" to URI.create("ws://backend-vip:8090"), "ws-chat" to URI.create("ws://chat-vip:8091")),
                routes.uris(),
            )
        }
    }
}

// 테스트 스레드(논블로킹 스레드 아님)에서 실행되므로 block()을 써도 BlockHound에 걸리지 않는다.
private fun RouteLocator.uris(): Map<String, URI> =
    routes
        .collectList()
        .block()!!
        .associate { it.id to it.uri }
