package com.example.wspoc.chat

import com.example.wspoc.auth.token.AuthPrincipal
import com.example.wspoc.wscore.protocol.WsSessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRoomsTest {
    private class FakeSession(
        override val id: String,
    ) : WsSessionContext {
        val received = AtomicInteger()
        override val principal = AuthPrincipal(id, emptySet(), Instant.MAX)
        override val anonymous = false
        override val serverId = "test"
        override val scope = CoroutineScope(Dispatchers.Unconfined)

        override suspend fun send(text: String) = Unit

        override fun trySend(text: String): WsSessionContext.Offer {
            received.incrementAndGet()
            return WsSessionContext.Offer.SENT
        }
    }

    /**
     * 마지막 멤버의 leave와 다른 세션의 join이 겹쳐도 join한 세션은 방에 남아야 한다.
     * 예전에는 join이 맵에서 꺼낸 set에 compute 밖에서 add해서, 그 사이 leave가 빈 set을 맵에서 지우면
     * join한 세션이 지워진 set에 들어가 메시지를 받지 못했다.
     */
    @Test
    fun `join racing with the last member leaving is not lost`() {
        val rooms = ChatRooms()
        val stop = AtomicBoolean()
        val churner = FakeSession("churner")
        // 방을 계속 비웠다 채웠다 하는 스레드: leave가 방을 지우는 순간을 반복해서 만든다.
        val churn =
            thread {
                while (!stop.get()) {
                    rooms.join(ROOM, churner)
                    rooms.leave(ROOM, churner)
                }
            }
        var lost = 0
        try {
            repeat(ROUNDS) {
                val joiner = FakeSession("joiner")
                rooms.join(ROOM, joiner)
                rooms.broadcast(ROOM, "hello")
                if (joiner.received.get() != 1) lost++
                rooms.leave(ROOM, joiner)
            }
        } finally {
            stop.set(true)
            churn.join()
        }

        assertEquals(0, lost, "joined sessions that did not receive the broadcast")
        assertEquals(0, rooms.members(ROOM))
    }

    private companion object {
        const val ROOM = "race"
        const val ROUNDS = 300_000
    }
}
