package com.example.wspoc.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * `ws.eventloop.lag`: 스레드 풀에 "[interval] 뒤에 실행"을 맡기고 실제로 얼마나 늦게 실행됐는지 잰다(실험 E1의 중단 기준).
 * Reactor Netty 내장 메트릭은 대기 중인 작업 수만 알려 주고 지연 시간은 주지 않는다.
 *
 * 스레드가 오래 걸리는 작업이나 밀린 작업 때문에 제때 돌지 못하면 값이 커진다. `pool` 태그로 풀을 구분한다
 * (`netty` = event loop, `parallel` = 세션 코루틴이 도는 Reactor parallel 스케줄러).
 * 측정 작업 자체는 기록 한 번과 다음 예약뿐이라 풀을 막지 않는다.
 */
class EventLoopLagProbe(
    private val registry: MeterRegistry,
    private val interval: Duration = Duration.ofMillis(100),
) : AutoCloseable {
    /** 측정 대상에 지연 실행을 맡기는 방법. 스레드 하나(event loop 하나)나 풀 전체가 될 수 있다. */
    fun interface Target {
        fun schedule(
            task: Runnable,
            delayMillis: Long,
        )
    }

    @Volatile
    private var stopped = false

    /** [target]을 측정하기 시작한다. event loop마다 재려면 loop마다 한 번씩 부른다(같은 [pool] 이름이면 한 Timer에 모인다). */
    fun watch(
        pool: String,
        target: Target,
    ) {
        val timer =
            Timer
                .builder(NAME)
                .description("예약한 작업이 예정보다 늦게 실행된 시간")
                .tag("pool", pool)
                .serviceLevelObjectives(*WsMeters.LATENCY_BUCKETS)
                .register(registry)
        scheduleNext(target, timer)
    }

    private fun scheduleNext(
        target: Target,
        timer: Timer,
    ) {
        if (stopped) return
        val expectedAt = System.nanoTime() + interval.toNanos()
        try {
            target.schedule(
                {
                    timer.record((System.nanoTime() - expectedAt).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    scheduleNext(target, timer)
                },
                interval.toMillis(),
            )
        } catch (_: RejectedExecutionException) {
            // 풀이 종료되는 중이다. 측정을 멈춘다.
        }
    }

    /** 다음 예약부터 멈춘다. 이미 예약된 작업은 한 번 더 돌고 끝난다. */
    override fun close() {
        stopped = true
    }

    companion object {
        const val NAME = "ws.eventloop.lag"
    }
}
