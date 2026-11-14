package com.example.wspoc.wscore.session

import java.util.concurrent.atomic.AtomicBoolean

/** 종료 시 한 번 켜진다. 그 뒤로 새 핸드셰이크는 거절된다. */
class DrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)
}
