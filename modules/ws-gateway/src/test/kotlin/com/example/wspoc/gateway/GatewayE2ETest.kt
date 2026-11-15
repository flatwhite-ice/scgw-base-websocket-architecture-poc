package com.example.wspoc.gateway

import com.example.wspoc.auth.token.GatewayHeaders
import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 클라이언트 -> ws-gateway(SCG, JWT 검증) -> ws-backend / ws-chat(gateway 인증 모드): relay 구조 전체 경로.
 * 게이트웨이는 경로로 서비스를 고른다(`/ws/echo`, `/ws/push` -> ws-backend, `/ws/chat` 이하 -> ws-chat).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayE2ETest.TestAuthConfig::class)
class GatewayE2ETest(
    @LocalServerPort private val port: Int,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class TestAuthConfig {
        @Bean
        fun testKeySource(): KeySource = TestKeys.keySource
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun backend(registry: DynamicPropertyRegistry) {
            registry.add("ws.gateway.auth.jwks-uri") { "" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-backend[0].uri") { "http://localhost:${RelayBackend.port}" }
            registry.add("spring.cloud.discovery.client.simple.instances.ws-chat[0].uri") { "http://localhost:${RelayChat.port}" }
        }
    }

    private val client = WsTestClient()

    private fun gw(
        path: String,
        query: String = "",
    ) = URI.create("ws://localhost:$port$path$query")

    @BeforeEach
    fun reset() {
        RelayBackend.seen.clear()
        RelayChat.seen.clear()
    }

    @Test
    fun `echo is relayed to the backend with the authenticated identity`() =
        runBlocking {
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("alice")}&foo=bar"))

            conn.sendJson("type" to "echo", "id" to "e1", "payload" to "via-gateway")
            val reply = conn.nextJson()

            assertEquals("e1", reply["id"].asString())
            assertEquals("via-gateway", reply["payload"].asString())
            assertEquals("alice", reply["userId"].asString())
            // 응답에는 게이트웨이가 아니라 처리한 backend 인스턴스가 찍힌다.
            assertTrue(reply["server"].asString().endsWith(":${RelayBackend.port}"), "server=${reply["server"]}")
            val seen = RelayBackend.seen.single()
            assertEquals("alice", seen.headers.getFirst(GatewayHeaders.USER_ID))
            assertFalse(
                seen.uri.query
                    .orEmpty()
                    .contains("ticket"),
                "ticket must not be forwarded: ${seen.uri}",
            )
            assertTrue(
                seen.uri.query
                    .orEmpty()
                    .contains("foo=bar"),
                "other query params are kept: ${seen.uri}",
            )
            conn.close()
        }

    @Test
    fun `bearer access token works through the gateway and is not forwarded`() =
        runBlocking {
            val headers = HttpHeaders().apply { setBearerAuth(TestKeys.accessToken("bob")) }
            val conn = client.connect(gw("/ws/echo"), headers)

            conn.sendJson("type" to "echo", "id" to "b1")

            assertEquals("bob", conn.nextJson()["userId"].asString())
            assertEquals(
                null,
                RelayBackend.seen
                    .single()
                    .headers
                    .getFirst(HttpHeaders.AUTHORIZATION),
            )
            conn.close()
        }

    @Test
    fun `unauthenticated upgrade gets 401 and no upstream connection is made`() =
        runBlocking {
            val noCreds = assertFailsWith<Exception> { client.connect(gw("/ws/echo")) }
            val badTicket = assertFailsWith<Exception> { client.connect(gw("/ws/echo", "?ticket=garbage")) }

            assertTrue(noCreds.message.orEmpty().contains("401"), "unexpected error: $noCreds")
            assertTrue(badTicket.message.orEmpty().contains("401"), "unexpected error: $badTicket")
            assertEquals(emptyList(), RelayBackend.seen, "backend must not see rejected handshakes")
            assertEquals(emptyList(), RelayChat.seen)
        }

    @Test
    fun `spoofed identity headers from the client are replaced`() =
        runBlocking {
            val spoofed =
                HttpHeaders().apply {
                    set(GatewayHeaders.USER_ID, "evil")
                    set(GatewayHeaders.SCOPES, "admin")
                    set(GatewayHeaders.AUTH_EXP, "9999999999")
                }
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("carol")}"), spoofed)

            conn.sendJson("type" to "echo", "id" to "s1")

            assertEquals("carol", conn.nextJson()["userId"].asString())
            val seen = RelayBackend.seen.single().headers
            assertEquals(listOf("carol"), seen[GatewayHeaders.USER_ID])
            assertEquals(listOf("ws"), seen[GatewayHeaders.SCOPES])
            assertTrue(seen.getFirst(GatewayHeaders.AUTH_EXP)!!.toLong() < 9999999999)
            conn.close()
        }

    @Test
    fun `spoofed identity headers alone do not authenticate`() =
        runBlocking {
            val spoofed = HttpHeaders().apply { set(GatewayHeaders.USER_ID, "evil") }

            val error = assertFailsWith<Exception> { client.connect(gw("/ws/echo"), spoofed) }

            assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
            assertEquals(emptyList(), RelayBackend.seen)
        }

    @Test
    fun `ticket cannot be replayed through the gateway`() =
        runBlocking {
            val ticket = TestKeys.ticket("dave")
            client.connect(gw("/ws/echo", "?ticket=$ticket")).close()

            val replay = assertFailsWith<Exception> { client.connect(gw("/ws/echo", "?ticket=$ticket")) }

            assertTrue(replay.message.orEmpty().contains("401"), "unexpected error: $replay")
        }

    @Test
    fun `push messages are relayed`() =
        runBlocking {
            val conn = client.connect(gw("/ws/push", "?ticket=${TestKeys.ticket("erin")}"))

            conn.sendJson("type" to "subscribe", "topic" to "relay", "intervalMs" to 50, "payloadBytes" to 64)
            val pushes =
                generateSequence { runBlocking { conn.nextJson() } }
                    .filter { it["topic"].asString() == "relay" }
                    .take(3)
                    .toList()

            assertEquals(listOf(0L, 1L, 2L), pushes.map { it["seq"].asLong() })
            assertTrue(pushes.all { it["payload"].asString().length == 64 })
            conn.close()
        }

    @Test
    fun `backend close code 3000 on token expiry reaches the client through the gateway`() =
        runBlocking {
            val conn = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("frank", sessionTtl = Duration.ofSeconds(2))}"))

            assertEquals(3000, conn.awaitClose(Duration.ofSeconds(6)).code)
        }

    @Test
    fun `chat path is routed to ws-chat and echo path to ws-backend`() =
        runBlocking {
            val alice = client.connect(gw("/ws/chat", "?ticket=${TestKeys.ticket("alice")}"))
            val bob = client.connect(gw("/ws/chat", "?ticket=${TestKeys.ticket("bob")}"))
            for (conn in listOf(alice, bob)) {
                conn.sendJson("type" to "join", "room" to "lobby")
                assertEquals("joined", conn.nextJson()["type"].asString())
            }

            alice.sendJson("type" to "say", "room" to "lobby", "text" to "via-gateway")

            for (conn in listOf(alice, bob)) {
                val msg = conn.nextJson()
                assertEquals("alice", msg["from"].asString())
                assertEquals("via-gateway", msg["text"].asString())
                assertTrue(msg["server"].asString().startsWith("ws-chat@"), "server=${msg["server"]}")
                assertTrue(msg["server"].asString().endsWith(":${RelayChat.port}"), "server=${msg["server"]}")
            }
            assertEquals(2, RelayChat.seen.size)
            assertEquals(listOf("alice", "bob"), RelayChat.seen.map { it.headers.getFirst(GatewayHeaders.USER_ID) })
            assertEquals(emptyList(), RelayBackend.seen, "chat handshakes must not reach ws-backend")

            val echo = client.connect(gw("/ws/echo", "?ticket=${TestKeys.ticket("carol")}"))
            echo.sendJson("type" to "echo", "id" to "r1")
            assertTrue(echo.nextJson()["server"].asString().startsWith("ws-backend@"))
            assertEquals(1, RelayBackend.seen.size)
            assertEquals(2, RelayChat.seen.size, "echo handshakes must not reach ws-chat")
            listOf(alice, bob, echo).forEach { it.close() }
        }

    @Test
    fun `public route needs no credentials and never forwards identity headers`() =
        runBlocking {
            // WsAuth가 없는 라우트: 자격증명 없이 연결된다. 위조 신원 헤더는 전역 필터가 제거한다.
            val spoofed = HttpHeaders().apply { set(GatewayHeaders.USER_ID, "evil") }
            val conn = client.connect(gw("/ws/public/push"), spoofed)

            val push = conn.nextJson()

            assertEquals("push", push["type"].asString())
            assertTrue(push["server"].asString().startsWith("ws-backend@"), "server=${push["server"]}")
            val seen = RelayBackend.seen.single().headers
            assertTrue(GatewayHeaders.ALL.none(seen::containsHeader), "identity headers leaked: $seen")
            conn.sendJson("type" to "subscribe", "intervalMs" to 10)
            assertEquals(
                "read only",
                generateSequence {
                    runBlocking { conn.nextJson() }
                }.first { it["type"].asString() == "error" }["reason"].asString(),
            )
            conn.close()
        }

    @Test
    fun `authenticated routes still reject anonymous upgrades with 401`() =
        runBlocking {
            for (path in listOf("/ws/echo", "/ws/push", "/ws/chat")) {
                val error = assertFailsWith<Exception> { client.connect(gw(path)) }
                assertTrue(error.message.orEmpty().contains("401"), "$path: unexpected error: $error")
            }
            assertEquals(emptyList(), RelayBackend.seen)
            assertEquals(emptyList(), RelayChat.seen)
        }

    @Test
    fun `ws path without a route gets 404 and reaches no service`() =
        runBlocking {
            // 매칭되는 라우트가 없으면 GlobalFilter(GatewayEdgeFilter)와 WsAuth 모두 실행되지 않는다: 자격증명이 있어도 404.
            val error = assertFailsWith<Exception> { client.connect(gw("/ws/unknown", "?ticket=${TestKeys.ticket("gus")}")) }

            assertTrue(error.message.orEmpty().contains("404"), "unexpected error: $error")
            assertEquals(emptyList(), RelayBackend.seen)
            assertEquals(emptyList(), RelayChat.seen)
        }

    @Test
    fun `sub-paths the services do not serve are not routed`() =
        runBlocking {
            // 라우트가 backend 엔드포인트보다 넓으면, 유효한 티켓을 쓰고도 101 직후 1002로 끊긴다(SCG의 101 선응답, D14).
            // 라우트를 실제 엔드포인트 경로로 좁혀 업그레이드 전에 404가 되게 한다(D24).
            val chat = assertFailsWith<Exception> { client.connect(gw("/ws/chat/room1", "?ticket=${TestKeys.ticket("hal")}")) }
            val public = assertFailsWith<Exception> { client.connect(gw("/ws/public/nope")) }

            assertTrue(chat.message.orEmpty().contains("404"), "unexpected error: $chat")
            assertTrue(public.message.orEmpty().contains("404"), "unexpected error: $public")
            assertEquals(emptyList(), RelayBackend.seen)
            assertEquals(emptyList(), RelayChat.seen)
        }

    @Test
    fun `non-websocket requests are not authenticated by the ws filter`() {
        // 라우트가 없는 경로로 일반 HTTP를 보내면 게이트웨이가 404를 준다. 필터는 업그레이드만 검사한다.
        val status =
            org.springframework.web.reactive.function.client.WebClient
                .create("http://localhost:$port")
                .get()
                .uri("/nope")
                .exchangeToMono {
                    reactor.core.publisher.Mono
                        .just(it.statusCode().value())
                }.block()
        assertEquals(404, status)
    }
}
