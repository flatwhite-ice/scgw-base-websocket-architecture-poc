package com.example.wspoc.support.drain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrainCoordinatorTest {
    private val window = Duration.ofSeconds(20)

    /** 세션 [count]개. [closes]가 false면 닫으라고 해도 닫히지 않는다(죽은 상대). */
    private class FakeTarget(
        count: Int,
        private val closes: Boolean = true,
    ) : DrainTarget {
        private val open = AtomicInteger(count)
        val closeCalls = AtomicInteger()

        override val activeCount: Int get() = open.get()

        override fun drainableSessions(): Collection<DrainableSession> =
            List(open.get()) {
                DrainableSession {
                    closeCalls.incrementAndGet()
                    if (closes) open.decrementAndGet()
                }
            }
    }

    @Test
    fun `stop closes every session within the window and then runs the callback`() =
        runTest {
            val state = DrainState()
            val target = FakeTarget(5)
            val drain = DrainCoordinator(window, state, target, "test sessions", StandardTestDispatcher(testScheduler))
            drain.start()
            var done = false

            drain.stop { done = true }
            assertTrue(state.draining, "new handshakes must be rejected as soon as drain starts")
            advanceUntilIdle()

            assertEquals(5, target.closeCalls.get())
            assertEquals(0, target.activeCount)
            assertTrue(done)
            assertFalse(drain.isRunning)
            assertTrue(currentTime <= window.toMillis() + 100, "finished at ${currentTime}ms, expected within the window")
        }

    @Test
    fun `stop gives up after the window plus grace when sessions stay open`() =
        runTest {
            val target = FakeTarget(2, closes = false)
            val drain = DrainCoordinator(window, DrainState(), target, "test sessions", StandardTestDispatcher(testScheduler))
            drain.start()
            var done = false

            drain.stop { done = true }
            advanceTimeBy(window.toMillis())
            assertFalse(done, "must still wait during the grace period")
            advanceUntilIdle()

            assertTrue(done)
            assertEquals(2, target.activeCount)
            assertEquals(window.toMillis() + 2_000, currentTime)
        }

    @Test
    fun `start clears the drain state so a restarted context accepts handshakes again`() {
        val state = DrainState()
        val drain = DrainCoordinator(window, state, FakeTarget(0), "test sessions")
        state.startDraining()

        drain.start()

        assertFalse(state.draining)
        assertTrue(drain.isRunning)
    }
}
