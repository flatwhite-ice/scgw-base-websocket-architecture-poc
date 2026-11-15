package com.example.wspoc.console

import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 콘솔 API를 auth-api와 게이트웨이 대신 작은 stub(Reactor Netty) 하나에 붙여 확인한다.
 * stub 규칙: `/ws/open`은 누구나 101, `/ws/secure`는 처음 쓰는 티켓이나 Bearer `tok-*`만 101(그 밖 401), 나머지 경로 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ConsoleControllerTest(
    @Autowired private val client: WebTestClient,
) {
    private fun probe(
        path: String,
        credential: String,
    ) = client
        .post()
        .uri("/api/probe")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("path" to path, "credential" to credential))
        .exchange()

    @Test
    fun `token and ticket requests are relayed with their status`() {
        client
            .post()
            .uri("/api/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("userId" to "alice", "ttlSeconds" to 10))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.token")
            .isEqualTo("tok-alice-10")
        client
            .post()
            .uri("/api/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("userId" to " "))
            .exchange()
            .expectStatus()
            .isBadRequest
        client
            .post()
            .uri("/api/ticket")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("token" to "tok-alice"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.ticket")
            .exists()
        client
            .post()
            .uri("/api/ticket")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("token" to "garbage"))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    private fun statuses(
        path: String,
        credential: String,
    ): List<Int?> =
        probe(path, credential)
            .expectStatus()
            .isOk
            .expectBody(ProbeResponse::class.java)
            .returnResult()
            .responseBody!!
            .results
            .map { it.status }

    @Test
    fun `probe reports the real handshake status`() {
        assertEquals(listOf(101), statuses("/ws/open", "none"))
        assertEquals(listOf(401), statuses("/ws/secure", "none"))
        assertEquals(listOf(401), statuses("/ws/secure", "spoof"))
        assertEquals(listOf(101), statuses("/ws/secure", "ticket"))
        assertEquals(listOf(101), statuses("/ws/secure", "bearer"))
        assertEquals(listOf(404), statuses("/ws/missing", "none"))
    }

    @Test
    fun `replay probe uses one ticket twice`() {
        assertEquals(listOf(101, 401), statuses("/ws/secure", "replay"))
    }

    @Test
    fun `probe accepts only ws paths and known credentials`() {
        for (path in listOf("/auth/token", "/ws/../x", "/ws/echo?ticket=x", "//evil.example/ws/echo", "/ws/")) {
            probe(path, "none").expectStatus().isBadRequest
        }
        probe("/ws/open", "magic").expectStatus().isBadRequest
    }

    @Test
    fun `config and static page are served`() {
        client
            .get()
            .uri("/api/config")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.wsBase")
            .isEqualTo("ws://localhost:${stub.port()}")
            // 화면이 curl 명령을 만들 때 쓰는 auth-api 주소
            .jsonPath("$.authBase")
            .isEqualTo("http://localhost:${stub.port()}")
        client
            .get()
            .uri("/")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("Cache-Control", "no-cache")
            .expectBody(String::class.java)
            .value { body -> check(body!!.contains("WS Console")) { "index.html not served" } }
        // 화면은 빌드 없는 ES 모듈이다: index.html이 불러오는 파일이 모두 서빙돼야 한다(app.js는 core.js를 import한다).
        for (asset in listOf("/app.js", "/core.js", "/style.css")) {
            client
                .get()
                .uri(asset)
                .exchange()
                .expectStatus()
                .isOk
                // 브라우저가 예전 JS/CSS를 캐시에서 쓰지 않게 매번 재검증시킨다.
                .expectHeader()
                .valueEquals("Cache-Control", "no-cache")
        }
    }

    companion object {
        private val tickets = ConcurrentHashMap.newKeySet<String>()

        // UUID.randomUUID()는 SecureRandom을 읽으므로(블로킹) event loop에서 쓰지 않는다.
        private val ticketSeq = AtomicLong()

        private val stub: DisposableServer =
            HttpServer
                .create()
                .port(0)
                .route { routes ->
                    routes
                        .post("/auth/token") { req, res ->
                            req.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                                val userId =
                                    Regex("\"userId\"\\s*:\\s*\"([^\"]*)\"")
                                        .find(body)
                                        ?.groupValues
                                        ?.get(1)
                                        .orEmpty()
                                val ttl = Regex("\"ttlSeconds\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
                                if (userId.isBlank()) {
                                    res.status(HttpResponseStatus.BAD_REQUEST).send().then()
                                } else {
                                    val token = listOfNotNull("tok", userId, ttl).joinToString("-")
                                    res.sendString(Mono.just("""{"token":"$token","expiresAt":"2030-01-01T00:00:00Z"}""")).then()
                                }
                            }
                        }.post("/auth/ws-ticket") { req, res ->
                            if (req.requestHeaders().get("Authorization")?.startsWith("Bearer tok-") == true) {
                                val ticket = "tkt-" + ticketSeq.incrementAndGet()
                                tickets.add(ticket)
                                res.sendString(Mono.just("""{"ticket":"$ticket","expiresAt":"2030-01-01T00:00:00Z"}"""))
                            } else {
                                res.status(HttpResponseStatus.UNAUTHORIZED).send()
                            }
                        }.get("/ws/open") { _, res -> res.sendWebsocket { inbound, _ -> inbound.receiveCloseStatus().then() } }
                        .get("/ws/secure") { req, res ->
                            val ticket = QueryStringDecoder(req.uri()).parameters()["ticket"]?.firstOrNull()
                            val bearer = req.requestHeaders().get("Authorization")?.startsWith("Bearer tok-") == true
                            if (bearer || (ticket != null && tickets.remove(ticket))) {
                                res.sendWebsocket { inbound, _ -> inbound.receiveCloseStatus().then() }
                            } else {
                                res.status(HttpResponseStatus.UNAUTHORIZED).send()
                            }
                        }
                }.bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun stubProperties(registry: DynamicPropertyRegistry) {
            registry.add("console.auth-base") { "http://localhost:${stub.port()}" }
            registry.add("console.ws-base") { "ws://localhost:${stub.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() = stub.disposeNow()
    }
}
