package com.example.wspoc.gateway.relay

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
 * relay 계층의 graceful drain. ws-backend의 DrainCoordinator와 같은 방식이다: 새 업그레이드를 받지 않고(503),
 * relay 중인 모든 클라이언트 세션을 `ws.gateway.drain.window` 안의 무작위 시점에 1001 Going Away로 닫는다.
 * 클라이언트가 한꺼번에 재연결하지 않게 하기 위해서다.
 *
 * 클라이언트 쪽만 닫으면 된다: SCG가 close를 backend 세션에도 전달한다(1001은 RFC 6455 코드라
 * adaptCloseStatus가 바꾸지 않는다. decisions D14). 가장 높은 lifecycle phase에서 실행되므로
 * 웹서버 graceful shutdown보다 먼저 동작한다.
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
        // 멈췄던 컨텍스트가 다시 시작되면(Spring 테스트의 컨텍스트 재사용) 업그레이드를 다시 받는다.
        drainState.reset()
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
                        // 취소 불가: close Mono가 취소되면 프레임이 전송되기 전에 해제된다(D9).
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
