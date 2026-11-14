package com.example.wspoc.gateway

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On shutdown, stop accepting new WebSocket upgrades (503) before the web server's graceful shutdown starts.
 * Relayed sessions are owned by Spring Cloud Gateway's WebsocketRoutingFilter and are not individually closed
 * with 1001 here; see docs/decisions.md D10.
 */
@Component
class GatewayDrainState : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val flag = AtomicBoolean(false)

    @Volatile
    private var running = false

    val draining: Boolean get() = flag.get()

    override fun start() {
        running = true
    }

    override fun stop() {
        flag.set(true)
        running = false
        log.info("Gateway draining: rejecting new WebSocket upgrades")
    }

    override fun isRunning() = running

    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE
}
