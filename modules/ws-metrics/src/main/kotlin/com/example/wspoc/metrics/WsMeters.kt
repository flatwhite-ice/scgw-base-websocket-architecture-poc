package com.example.wspoc.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket 모듈 공통 메트릭(스펙 §5.5, D27). 서비스(ws-backend-core)와 게이트웨이가 같은 이름을 쓴다.
 * 어느 프로세스의 값인지는 Prometheus 공통 태그 `application`으로 구분한다.
 *
 * [scopeTag]는 세션을 묶는 단위의 태그 이름이다: 서비스는 `endpoint`(`@WsEndpoint` 경로), 게이트웨이는 `route`(라우트 id).
 * 태그 값은 모두 개수가 한정된 것만 쓴다. userId, 방 이름, 세션 id, 요청 경로 원문은 태그로 쓰지 않는다.
 *
 * 메시지마다 호출되는 것은 [Scope]에 미리 만들어 둔 Counter의 `increment()`뿐이다. 메시지 단위 Timer는 두지 않는다.
 */
class WsMeters(
    private val registry: MeterRegistry,
    private val scopeTag: String,
) {
    private val scopes = ConcurrentHashMap<String, Scope>()

    /** [value](엔드포인트 경로 또는 라우트 id)의 미터 묶음. 처음 부를 때 등록하고 이후에는 같은 것을 돌려준다. */
    fun scope(value: String): Scope = scopes[value] ?: scopes.computeIfAbsent(value, ::Scope)

    /**
     * `ws.handshake`: 핸드셰이크 한 건의 결과와 소요 시간.
     * [result]는 [HandshakeResult]의 값, [reason]은 `unauthorized`일 때의 사유(그 외에는 `none`)다.
     */
    fun handshake(
        scope: String,
        result: String,
        reason: String,
        nanos: Long,
    ) {
        Timer
            .builder(HANDSHAKE)
            .description("WebSocket 핸드셰이크 결과와 소요 시간")
            .tag(scopeTag, scope)
            .tag("result", result)
            .tag("reason", reason)
            .serviceLevelObjectives(*LATENCY_BUCKETS)
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }

    /** 핸드셰이크 시작 시각을 잡아 둔다. 결과가 정해지는 곳에서 [HandshakeTrace.record]를 부른다. */
    fun startHandshake(scope: String): HandshakeTrace = HandshakeTrace(this, scope, System.nanoTime())

    /** `ws.relay.upstream.connect`(게이트웨이 전용): upstream WebSocket 연결 결과와 소요 시간. */
    fun upstreamConnect(
        scope: String,
        error: Throwable?,
        nanos: Long,
    ) {
        Timer
            .builder(UPSTREAM_CONNECT)
            .description("게이트웨이가 upstream으로 WebSocket을 여는 데 걸린 시간")
            .tag(scopeTag, scope)
            .tag("result", if (error == null) "ok" else "error")
            .tag("exception", error?.javaClass?.simpleName ?: "none")
            .serviceLevelObjectives(*LATENCY_BUCKETS)
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }

    inner class Scope internal constructor(
        private val value: String,
    ) {
        private val active = AtomicInteger()

        /** `ws.messages{direction=in}`: 클라이언트가 보낸 데이터 프레임(TEXT/BINARY). ping/pong은 세지 않는다. */
        val messagesIn: Counter = messages("in")

        /** `ws.messages{direction=out}`: 클라이언트로 보낸 데이터 프레임. */
        val messagesOut: Counter = messages("out")

        /**
         * `ws.messages.dropped`: 송신 큐가 가득 차서(느린 소비자) 버린 메시지. 처음 쓸 때 등록한다:
         * 송신 큐가 없는 게이트웨이에는 이 시계열이 생기지 않는다.
         */
        val messagesDropped: Counter by lazy {
            Counter
                .builder(MESSAGES_DROPPED)
                .description("송신 큐가 가득 차서 버린 메시지 수")
                .tag(scopeTag, value)
                .register(registry)
        }

        private val duration: Timer =
            Timer
                .builder(SESSION_DURATION)
                .description("세션이 열려 있던 시간")
                .tag(scopeTag, value)
                .register(registry)

        init {
            Gauge
                .builder(SESSIONS_ACTIVE, active) { it.get().toDouble() }
                .description("지금 열려 있는 WebSocket 세션 수")
                .tag(scopeTag, value)
                .register(registry)
        }

        val activeSessions: Int get() = active.get()

        fun sessionOpened() {
            active.incrementAndGet()
        }

        /** 세션이 끝났다. [sessionOpened]와 반드시 짝을 맞춘다. close 코드는 늦게 알 수 있어 [closed]로 따로 받는다. */
        fun sessionEnded(durationNanos: Long) {
            active.decrementAndGet()
            duration.record(durationNanos, TimeUnit.NANOSECONDS)
        }

        /** `ws.close`: 세션이 끝난 close 코드. 코드 없이 끊기면 1005를 넘긴다. */
        fun closed(code: Int) {
            Counter
                .builder(CLOSE)
                .description("close 코드별 세션 종료 수")
                .tag(scopeTag, value)
                .tag("code", closeCodeTag(code))
                .register(registry)
                .increment()
        }

        private fun messages(direction: String): Counter =
            Counter
                .builder(MESSAGES)
                .description("WebSocket 데이터 프레임 수")
                .tag(scopeTag, value)
                .tag("direction", direction)
                .register(registry)
    }

    companion object {
        const val SESSIONS_ACTIVE = "ws.sessions.active"
        const val HANDSHAKE = "ws.handshake"
        const val MESSAGES = "ws.messages"
        const val MESSAGES_DROPPED = "ws.messages.dropped"
        const val CLOSE = "ws.close"
        const val SESSION_DURATION = "ws.session.duration"
        const val UPSTREAM_CONNECT = "ws.relay.upstream.connect"

        /** 매칭되는 엔드포인트나 라우트가 없을 때의 scope 값. */
        const val UNKNOWN_SCOPE = "unknown"

        /** 히스토그램 버킷. 백분위는 Prometheus에서 `histogram_quantile`로 구한다. */
        val LATENCY_BUCKETS: Array<Duration> =
            longArrayOf(1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500).map(Duration::ofMillis).toTypedArray()

        /**
         * close 코드를 태그 값으로 바꾼다. 클라이언트는 임의의 코드를 보낼 수 있으므로 RFC 6455 §7.4.1 범위(1000–1015)와
         * 이 프로젝트가 쓰는 애플리케이션 코드(3000, 3008)만 그대로 두고 나머지는 `other`로 묶는다.
         */
        fun closeCodeTag(code: Int): String = if (code in 1000..1015 || code == 3000 || code == 3008) code.toString() else "other"

        /**
         * Reactor Netty 내장 HTTP 메트릭의 `uri` 태그 값. 요청 경로를 그대로 쓰면 시계열이 끝없이 늘어나므로
         * 첫 구간만 남긴다(`/ws/echo` → `/ws`, `/actuator/prometheus` → `/actuator`). 그 밖의 경로는 `other`다.
         */
        fun uriTag(path: String): String =
            when {
                path.startsWith("/ws/") -> "/ws"
                path.startsWith("/actuator") -> "/actuator"
                else -> "other"
            }
    }
}

/** `ws.handshake`의 `result` 태그 값. */
object HandshakeResult {
    /** 업그레이드 응답(101)이 나갔다. */
    const val OK = "ok"

    /** 401. 사유는 `reason` 태그에 있다. */
    const val UNAUTHORIZED = "unauthorized"

    /** drain 중이라 503으로 거절했다. */
    const val DRAINING = "draining"

    /** 인증은 통과했지만 세션이 열리지 않았다(없는 경로, 업그레이드가 아닌 요청, 업그레이드 실패). */
    const val ERROR = "error"

    /** `unauthorized`가 아닌 결과의 `reason` 태그 값. */
    const val NO_REASON = "none"
}

/**
 * 진행 중인 핸드셰이크 한 건. 결과가 여러 곳에서 정해질 수 있어서(인증 필터의 거절, 업그레이드 완료, 체인 종료)
 * 처음 불린 [record]만 기록한다.
 */
class HandshakeTrace internal constructor(
    private val meters: WsMeters,
    val scope: String,
    private val startNanos: Long,
) {
    private val recorded = AtomicBoolean()

    fun record(
        result: String,
        reason: String = HandshakeResult.NO_REASON,
    ) {
        if (recorded.compareAndSet(false, true)) meters.handshake(scope, result, reason, System.nanoTime() - startNanos)
    }
}
