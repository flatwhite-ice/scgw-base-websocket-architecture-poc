package com.example.wspoc.console

import io.netty.handler.codec.http.HttpResponseStatus
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `/api/metrics`를 stub 대상 셋에 붙여 확인한다: 정상 대상, 메트릭 경로가 없는 대상(404), 떠 있지 않은 대상.
 * 실패한 대상이 있어도 응답은 200이고 그 대상만 down이어야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MonitorMetricsTest(
    @Autowired private val client: WebTestClient,
) {
    @Test
    fun `scrapes every target and reports failed ones as down`() {
        client
            .get()
            .uri("/api/metrics")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.timestamp")
            .isNumber
            .jsonPath("$.refreshMillis")
            .isEqualTo(60000)
            .jsonPath("$.targets.length()")
            .isEqualTo(3)
            // 대상은 설정한 순서대로 나온다.
            .jsonPath("$.targets[0].name")
            .isEqualTo("up")
            .jsonPath("$.targets[0].up")
            .isEqualTo(true)
            // 화면이 쓰는 메트릭만 남고 application label은 빠진다.
            .jsonPath("$.targets[0].samples.length()")
            .isEqualTo(3)
            .jsonPath("$.targets[0].samples[0].name")
            .isEqualTo("ws_sessions_active")
            .jsonPath("$.targets[0].samples[0].labels.route")
            .isEqualTo("ws-chat")
            .jsonPath("$.targets[0].samples[0].labels.application")
            .doesNotExist()
            .jsonPath("$.targets[0].samples[0].value")
            .isEqualTo(3.0)
            .jsonPath("$.targets[0].samples[2].labels.le")
            .isEqualTo("+Inf")
            .jsonPath("$.targets[1].name")
            .isEqualTo("no-actuator")
            .jsonPath("$.targets[1].up")
            .isEqualTo(false)
            .jsonPath("$.targets[1].error")
            .isEqualTo("HTTP 404")
            .jsonPath("$.targets[2].up")
            .isEqualTo(false)
            .jsonPath("$.targets[2].samples.length()")
            .isEqualTo(0)
    }

    @Test
    fun `reuses a recent scrape instead of hitting the targets again`() {
        repeat(3) {
            client
                .get()
                .uri("/api/metrics")
                .exchange()
                .expectStatus()
                .isOk
        }
        // refresh가 60초이므로 30초 동안은 같은 결과를 쓴다(다른 테스트의 호출까지 합쳐도 한 번).
        assertEquals(1, scrapes.get())
    }

    companion object {
        private val scrapes = AtomicInteger()

        private val body =
            """
            # TYPE ws_sessions_active gauge
            ws_sessions_active{application="ws-gateway",route="ws-chat"} 3.0
            process_cpu_usage{application="ws-gateway"} 0.25
            http_server_requests_seconds_count{application="ws-gateway",uri="/x"} 9
            ws_handshake_seconds_bucket{application="ws-gateway",le="+Inf",result="ok"} 7
            ws_handshake_seconds_max{application="ws-gateway",result="ok"} NaN
            """.trimIndent()

        private val up: DisposableServer =
            HttpServer
                .create()
                .port(0)
                .route { routes ->
                    routes.get("/actuator/prometheus") { _, res ->
                        scrapes.incrementAndGet()
                        res.header("Content-Type", "text/plain; version=0.0.4").sendString(Mono.just(body))
                    }
                }.bindNow()

        private val noActuator: DisposableServer =
            HttpServer
                .create()
                .port(0)
                .route { routes -> routes.get("/other") { _, res -> res.status(HttpResponseStatus.OK).send() } }
                .bindNow()

        // 열었다가 닫은 포트: 연결이 거절된다.
        private val deadPort: Int =
            HttpServer
                .create()
                .port(0)
                .bindNow()
                .let { server -> server.port().also { server.disposeNow() } }

        @JvmStatic
        @DynamicPropertySource
        fun targets(registry: DynamicPropertyRegistry) {
            registry.add("console.monitor.targets[0].name") { "up" }
            registry.add("console.monitor.targets[0].url") { "http://localhost:${up.port()}" }
            registry.add("console.monitor.targets[1].name") { "no-actuator" }
            registry.add("console.monitor.targets[1].url") { "http://localhost:${noActuator.port()}" }
            registry.add("console.monitor.targets[2].name") { "dead" }
            registry.add("console.monitor.targets[2].url") { "http://localhost:$deadPort" }
            registry.add("console.monitor.refresh") { "60s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            up.disposeNow()
            noActuator.disposeNow()
        }
    }
}
