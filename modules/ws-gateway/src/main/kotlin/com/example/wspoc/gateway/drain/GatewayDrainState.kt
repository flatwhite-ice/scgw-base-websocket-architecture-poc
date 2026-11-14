package com.example.wspoc.gateway.drain

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** 종료 시 [GatewayDrainCoordinator]가 한 번 켠다. 그 뒤로 GatewayEdgeFilter는 새 업그레이드를 503으로 거절한다(모든 라우트). */
@Component
class GatewayDrainState {
    private val flag = AtomicBoolean(false)

    val draining: Boolean get() = flag.get()

    fun startDraining() = flag.set(true)
}
