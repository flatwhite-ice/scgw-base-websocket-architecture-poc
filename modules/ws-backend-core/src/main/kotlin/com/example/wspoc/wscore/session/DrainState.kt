package com.example.wspoc.wscore.session

import java.util.concurrent.atomic.AtomicBoolean

/** 종료 시 켜진다. 그 뒤로 새 핸드셰이크는 거절된다. 컨텍스트가 다시 시작되면 [reset]으로 꺼진다. */
class DrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)

    fun reset() = flag.set(false)
}
