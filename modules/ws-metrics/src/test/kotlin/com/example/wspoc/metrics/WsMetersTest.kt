package com.example.wspoc.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WsMetersTest {
    private val registry = SimpleMeterRegistry()
    private val meters = WsMeters(registry, "endpoint")

    @Test
    fun `a scope registers its meters once and tracks active sessions`() {
        val scope = meters.scope("/ws/echo")
        assertSame(scope, meters.scope("/ws/echo"))

        scope.sessionOpened()
        scope.sessionOpened()
        assertEquals(
            2.0,
            registry
                .get(WsMeters.SESSIONS_ACTIVE)
                .tag("endpoint", "/ws/echo")
                .gauge()
                .value(),
        )

        scope.sessionEnded(1_000_000)
        scope.closed(1001)
        assertEquals(
            1.0,
            registry
                .get(WsMeters.SESSIONS_ACTIVE)
                .tag("endpoint", "/ws/echo")
                .gauge()
                .value(),
        )
        assertEquals(
            1L,
            registry
                .get(WsMeters.SESSION_DURATION)
                .tag("endpoint", "/ws/echo")
                .timer()
                .count(),
        )
        assertEquals(
            1.0,
            registry
                .get(WsMeters.CLOSE)
                .tags("endpoint", "/ws/echo", "code", "1001")
                .counter()
                .count(),
        )
    }

    @Test
    fun `message counters are split by direction`() {
        val scope = meters.scope("/ws/push")
        scope.messagesIn.increment()
        scope.messagesOut.increment(3.0)
        scope.messagesDropped.increment()

        assertEquals(
            1.0,
            registry
                .get(WsMeters.MESSAGES)
                .tags("endpoint", "/ws/push", "direction", "in")
                .counter()
                .count(),
        )
        assertEquals(
            3.0,
            registry
                .get(WsMeters.MESSAGES)
                .tags("endpoint", "/ws/push", "direction", "out")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            registry
                .get(WsMeters.MESSAGES_DROPPED)
                .tag("endpoint", "/ws/push")
                .counter()
                .count(),
        )
    }

    @Test
    fun `a handshake is recorded once, by the first result`() {
        val trace = meters.startHandshake("/ws/echo")
        trace.record(HandshakeResult.UNAUTHORIZED, "replayed")
        // 체인이 끝날 때 다시 불려도 처음 결과가 남는다.
        trace.record(HandshakeResult.ERROR)

        val timers = registry.find(WsMeters.HANDSHAKE).timers()
        assertEquals(1, timers.size)
        assertEquals("unauthorized", timers.single().id.getTag("result"))
        assertEquals("replayed", timers.single().id.getTag("reason"))
        assertEquals(1L, timers.single().count())
    }

    @Test
    fun `upstream connect failures carry the exception class`() {
        meters.upstreamConnect("ws-chat", null, 1_000)
        meters.upstreamConnect("ws-chat", IllegalStateException("boom"), 1_000)

        assertEquals(
            1L,
            registry
                .get(WsMeters.UPSTREAM_CONNECT)
                .tags("result", "ok", "exception", "none")
                .timer()
                .count(),
        )
        assertEquals(
            1L,
            registry
                .get(WsMeters.UPSTREAM_CONNECT)
                .tags("result", "error", "exception", "IllegalStateException")
                .timer()
                .count(),
        )
    }

    @Test
    fun `close codes outside the known set collapse into other`() {
        listOf(1000, 1001, 1002, 1005, 1006, 1008, 1011, 3000, 3008).forEach { assertEquals(it.toString(), WsMeters.closeCodeTag(it)) }
        // 클라이언트가 임의로 보낼 수 있는 코드는 시계열을 늘리지 않는다.
        listOf(999, 1016, 2999, 3001, 4000, 4999).forEach { assertEquals("other", WsMeters.closeCodeTag(it)) }
    }

    @Test
    fun `uri tag keeps only the first path segment`() {
        assertEquals("/ws", WsMeters.uriTag("/ws/echo"))
        assertEquals("/ws", WsMeters.uriTag("/ws/chat/anything/else"))
        assertEquals("/actuator", WsMeters.uriTag("/actuator/prometheus"))
        assertEquals("other", WsMeters.uriTag("/favicon.ico"))
    }
}
