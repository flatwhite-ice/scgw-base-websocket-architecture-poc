package com.example.wspoc.chat

import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import com.example.wspoc.wscore.testing.WsTestConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SelfModeWsTest
class ChatTest(
    @LocalServerPort private val port: Int,
    @Autowired private val rooms: ChatRooms,
) {
    private val client = WsTestClient()

    private suspend fun connect(
        userId: String,
        path: String = "/ws/chat",
    ) = client.connect(URI.create("ws://localhost:$port$path?ticket=${TestKeys.ticket(userId)}"))

    private suspend fun WsTestConnection.join(room: String) {
        sendJson("type" to "join", "room" to room)
        val joined = nextJson()
        assertEquals("joined", joined["type"].asString())
        assertEquals(room, joined["room"].asString())
    }

    /** 다른 세션의 해제가 반영될 때까지 기다린다(onClose는 세션 코루틴이 끝날 때 비동기로 실행된다). */
    private suspend fun awaitMembers(
        room: String,
        expected: Int,
    ) = withTimeout(3_000) { while (rooms.members(room) != expected) delay(20) }

    @Test
    fun `message is delivered to every member of the room including the sender`() =
        runBlocking {
            val alice = connect("alice")
            val bob = connect("bob")
            alice.join("r1")
            bob.join("r1")

            alice.sendJson("type" to "say", "room" to "r1", "text" to "hi")

            for (conn in listOf(alice, bob)) {
                val msg = conn.nextJson()
                assertEquals("message", msg["type"].asString())
                assertEquals("r1", msg["room"].asString())
                assertEquals("alice", msg["from"].asString())
                assertEquals("hi", msg["text"].asString())
                assertTrue(msg["server"].asString().matches(Regex("ws-chat@.+:$port")), "server=${msg["server"]}")
            }
            alice.close()
            bob.close()
        }

    @Test
    fun `rooms are isolated and leave or disconnect stops delivery`() =
        runBlocking {
            val alice = connect("alice2")
            val bob = connect("bob2")
            val carol = connect("carol2")
            alice.join("r2")
            bob.join("r2")
            carol.join("other")

            bob.sendJson("type" to "leave", "room" to "r2")
            assertEquals("left", bob.nextJson()["type"].asString())
            carol.close()
            awaitMembers("r2", 1)
            awaitMembers("other", 0)

            alice.sendJson("type" to "say", "room" to "r2", "text" to "only me")

            assertEquals("only me", alice.nextJson()["text"].asString())
            assertNull(runCatching { bob.nextJson(Duration.ofMillis(300)) }.getOrNull(), "bob left the room")
            alice.close()
            awaitMembers("r2", 0)
            bob.close()
        }

    @Test
    fun `say without join and unknown types get error replies without closing`() =
        runBlocking {
            val conn = connect("dave")

            conn.sendJson("type" to "say", "room" to "nowhere", "text" to "x")
            assertEquals("not joined", conn.nextJson()["reason"].asString())
            conn.sendJson("type" to "join")
            assertEquals("room required", conn.nextJson()["reason"].asString())
            conn.sendJson("type" to "echo")
            assertEquals("unknown type", conn.nextJson()["reason"].asString())
            conn.send("{not json")
            assertEquals("invalid json", conn.nextJson()["reason"].asString())
            conn.join("still-open")
            conn.close()
        }

    @Test
    fun `unauthenticated handshake is rejected with 401`() =
        runBlocking {
            val error = assertFailsWith<Exception> { client.connect(URI.create("ws://localhost:$port/ws/chat")) }
            assertTrue(error.message.orEmpty().contains("401"), "unexpected error: $error")
        }

    @Test
    fun `echo endpoint belongs to ws-backend and is not served here`() =
        runBlocking {
            val error = assertFailsWith<Exception> { connect("erin", path = "/ws/echo") }
            assertTrue(error.message.orEmpty().contains("404"), "unexpected error: $error")
        }
}
