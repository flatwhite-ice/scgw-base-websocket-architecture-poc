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

/** application.yml / application-static.yml의 라우트 정의가 의도한 대상으로 바인딩되는지 확인한다. */
class RouteDefinitionTest {
    @Nested
    @SpringBootTest(properties = ["ws.gateway.auth.jwks-uri="])
    @Import(WsGatewayApplicationTests.Keys::class)
    inner class Discovery(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `default route goes through the load balancer`() {
            assertEquals(URI.create("lb:ws://ws-backend"), routes.wsBackendUri())
        }
    }

    @Nested
    @SpringBootTest(properties = ["ws.gateway.auth.jwks-uri=", "WS_BACKEND_URI=ws://backend-vip:8081"])
    @Import(WsGatewayApplicationTests.Keys::class)
    @ActiveProfiles("static")
    inner class Static(
        @Autowired val routes: RouteLocator,
    ) {
        @Test
        fun `static profile routes to a fixed uri`() {
            assertEquals(URI.create("ws://backend-vip:8081"), routes.wsBackendUri())
        }
    }
}

// 테스트 스레드(논블로킹 스레드 아님)에서 실행되므로 block()을 써도 BlockHound에 걸리지 않는다.
private fun RouteLocator.wsBackendUri(): URI =
    routes
        .collectList()
        .block()!!
        .single { it.id == "ws-backend" }
        .uri
