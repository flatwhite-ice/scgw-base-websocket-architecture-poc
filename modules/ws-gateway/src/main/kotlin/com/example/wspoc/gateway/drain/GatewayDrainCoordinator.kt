package com.example.wspoc.gateway.drain

import com.example.wspoc.gateway.GatewayProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import reactor.core.scheduler.Schedulers
import kotlin.random.Random

/**
 * Graceful drain of the relay tier, mirroring ws-backend's DrainCoordinator: stop accepting upgrades (503), then
 * close every relayed client session with 1001 Going Away at a random point inside `ws.gateway.drain.window`, so
 * clients do not all reconnect at once.
 *
 * Closing the client side is enough: SCG propagates the close to the backend session (1001 is an RFC 6455 code,
 * so its adaptCloseStatus passes it through unchanged; decisions D14). Runs at the highest lifecycle phase, i.e.
 * before the web server's graceful shutdown.
 */
@Component
class GatewayDrainCoordinator(
    private val props: GatewayProperties,
    private val drainState: GatewayDrainState,
    private val registry: RelaySessionRegistry,
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
        log.info("Gateway draining {} relayed WebSocket sessions over {}", sessions.size, window)
        val scope = CoroutineScope(SupervisorJob() + Schedulers.parallel().asCoroutineDispatcher())
        scope.launch {
            try {
                sessions.forEach { session ->
                    launch {
                        delay(Random.nextLong(window.toMillis().coerceAtLeast(1)))
                        // Not cancellable: a cancelled close Mono releases the frame before it is written (D9).
                        withContext(NonCancellable) { session.close(CloseStatus.GOING_AWAY).awaitSingleOrNull() }
                    }
                }
                withTimeoutOrNull(window.toMillis() + GRACE_MILLIS) {
                    while (registry.activeCount > 0) delay(POLL_MILLIS)
                }
                log.info("Gateway drain finished, {} relayed sessions still open", registry.activeCount)
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
