package com.example.wspoc.wscore.session

import com.example.wspoc.wscore.WsProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import kotlin.random.Random

/**
 * 종료 시 graceful drain: 새 핸드셰이크를 받지 않고, 모든 세션을 `ws.drain.window` 안의 무작위 시점에
 * 1001 Going Away로 닫는다. 클라이언트가 한꺼번에 재연결하지 않게 하기 위해서다.
 *
 * 가장 높은 lifecycle phase에서 실행된다. 즉 Boot 웹서버 graceful shutdown(DEFAULT_PHASE - 1024)보다
 * *먼저* stop되므로, 세션을 닫는 동안 서버는 아직 살아 있다.
 */
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
        // 멈췄던 컨텍스트가 다시 시작되면 핸드셰이크를 다시 받는다. Spring 테스트는 쓰지 않는 컨텍스트를 멈췄다가
        // 재사용할 때 다시 시작하므로, 이게 없으면 재사용된 컨텍스트가 계속 503을 준다.
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
                // 지터를 둔 close들과, close 핸드셰이크가 끝날 짧은 유예 시간을 기다린다.
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
