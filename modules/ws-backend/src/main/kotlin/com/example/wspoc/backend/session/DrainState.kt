package com.example.wspoc.backend.session

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Flipped once on shutdown; new handshakes are rejected from then on. */
@Component
class DrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)
}
