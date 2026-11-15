package com.example.wspoc.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLoopLagProbeTest {
    private val registry = SimpleMeterRegistry()
    private val executor = Executors.newSingleThreadScheduledExecutor()

    private fun timer() = registry.get(EventLoopLagProbe.NAME).tag("pool", "test").timer()

    @Test
    fun `a busy thread shows up as lag`() {
        EventLoopLagProbe(registry, Duration.ofMillis(10)).use { probe ->
            probe.watch("test") { task, delay -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) }
            // 스레드를 200ms 붙잡는다. 그동안 예약된 측정 작업은 그만큼 늦게 실행된다.
            executor.submit { Thread.sleep(200) }.get()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (timer().max(TimeUnit.MILLISECONDS) < 100 && System.nanoTime() < deadline) Thread.sleep(10)

            assertTrue(timer().max(TimeUnit.MILLISECONDS) >= 100, "max lag = ${timer().max(TimeUnit.MILLISECONDS)}ms")
        }
        executor.shutdownNow()
    }

    @Test
    fun `close stops rescheduling`() {
        val probe = EventLoopLagProbe(registry, Duration.ofMillis(5))
        probe.watch("test") { task, delay -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) }
        probe.close()
        // 이미 예약된 한 번까지 지나가게 둔 뒤, 더는 늘지 않는지 본다.
        val drained = CountDownLatch(1)
        executor.schedule({ drained.countDown() }, 50, TimeUnit.MILLISECONDS)
        drained.await()
        val count = timer().count()
        Thread.sleep(50)

        assertEquals(count, timer().count())
        executor.shutdownNow()
    }

    @Test
    fun `a rejected schedule ends the probe quietly`() {
        executor.shutdownNow()
        EventLoopLagProbe(
            registry,
            Duration.ofMillis(5),
        ).watch("test") { task, delay -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) }

        assertEquals(0L, timer().count())
    }
}
