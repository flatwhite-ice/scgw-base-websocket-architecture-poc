package com.example.wspoc.backend.session

import com.example.wspoc.backend.WsProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * Graceful drain on shutdown: stop accepting handshakes, then close every session with 1001 at a random
 * point inside `ws.drain.window` so clients do not all reconnect at the same instant.
 *
 * Runs at the highest lifecycle phase, i.e. it is stopped *before* Boot's web server graceful shutdown
 * (DEFAULT_PHASE - 1024), so the server is still up while sessions are being closed.
 */
@Component
class DrainCoordinator(
    private val props: WsProperties,
    private val drainState: DrainState,
    private val registry: SessionRegistry,
    private val sessionScope: SessionScope,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun isRunning() = running

    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE

    override fun stop() = throw UnsupportedOperationException("use stop(callback)")

    override fun stop(callback: Runnable) {
        drainState.startDraining()
        val sessions = registry.snapshot()
        val window = props.drain.window
        log.info("Draining {} WebSocket sessions over {}", sessions.size, window)
        val scope = CoroutineScope(SupervisorJob() + sessionScope.dispatcher)
        scope.launch {
            try {
                sessions.values.forEach { handle ->
                    launch {
                        delay(Random.nextLong(window.toMillis().coerceAtLeast(1)))
                        handle.closeAsync(CloseCodes.GOING_AWAY)
                    }
                }
                // Wait for the jittered closes plus a short grace for close handshakes to finish.
                withTimeoutOrNull(window.toMillis() + GRACE_MILLIS) {
                    while (registry.activeCount > 0) delay(POLL_MILLIS)
                }
                log.info("Drain finished, {} sessions still open", registry.activeCount)
            } finally {
                running = false
                callback.run()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val GRACE_MILLIS = 2_000L
        const val POLL_MILLIS = 50L
    }
}
