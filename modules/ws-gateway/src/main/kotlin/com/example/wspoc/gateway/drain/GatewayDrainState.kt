package com.example.wspoc.gateway.drain

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Flipped once on shutdown by [GatewayDrainCoordinator]; WsAuthFilter rejects new upgrades with 503 from then on. */
@Component
class GatewayDrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)
}
