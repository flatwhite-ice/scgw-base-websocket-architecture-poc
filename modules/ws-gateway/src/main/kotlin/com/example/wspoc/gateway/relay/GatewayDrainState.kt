package com.example.wspoc.gateway.relay

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** 종료 시 [GatewayDrainCoordinator]가 켠다. 그 뒤로 GatewayEdgeFilter는 새 업그레이드를 503으로 거절한다(모든 라우트). */
@Component
class GatewayDrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)

    /** 멈췄던 컨텍스트가 다시 시작될 때 끈다. */
    fun reset() = flag.set(false)
}
