package com.example.wspoc.support.drain

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.SmartLifecycle
import reactor.core.scheduler.Schedulers
import java.time.Duration
import kotlin.random.Random

/** drain이 닫을 수 있는 살아 있는 세션 하나. */
fun interface DrainableSession {
    /** 1001 Going Away로 닫는다. close 프레임이 전송 전에 사라지지 않게 하는 것은 구현하는 쪽의 책임이다(D9). */
    suspend fun closeGoingAway()
}

/** drain 대상: 이 프로세스가 지금 갖고 있는 세션들(서비스의 세션, 게이트웨이가 relay 중인 클라이언트 세션). */
interface DrainTarget {
    val activeCount: Int

    /** 지금 열려 있는 세션들. drain을 시작할 때 한 번 찍는다. 그 뒤에 등록된 세션은 등록한 쪽이 스스로 닫는다(D24). */
    fun drainableSessions(): Collection<DrainableSession>
}

/**
 * 종료 시 graceful drain: 새 핸드셰이크를 받지 않고([DrainState]), 모든 세션을 [window] 안의 무작위 시점에
 * 1001 Going Away로 닫는다. 클라이언트가 한꺼번에 재연결하지 않게 하기 위해서다.
 *
 * 가장 높은 lifecycle phase에서 실행된다. 즉 Boot 웹서버 graceful shutdown(DEFAULT_PHASE - 1024)보다
 * *먼저* stop되므로, 세션을 닫는 동안 서버는 아직 살아 있다.
 *
 * 서비스(ws-backend-core)와 게이트웨이가 함께 쓴다. 무엇을 어떻게 닫는지는 [target]이 정하고,
 * [subject]는 로그에 쓰는 대상 이름이다(예: `WebSocket sessions`).
 */
class DrainCoordinator(
    private val window: Duration,
    private val drainState: DrainState,
    private val target: DrainTarget,
    private val subject: String,
    private val dispatcher: CoroutineDispatcher = Schedulers.parallel().asCoroutineDispatcher(),
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        // 멈췄던 컨텍스트가 다시 시작되면 핸드셰이크를 다시 받는다. Spring 테스트는 쓰지 않는 컨텍스트를 멈췄다가
        // 재사용할 때 다시 시작하므로, 이게 없으면 재사용된 컨텍스트가 계속 503을 준다(D27).
        drainState.reset()
        running = true
    }

    override fun isRunning() = running

    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE

    override fun stop() = throw UnsupportedOperationException("use stop(callback)")

    override fun stop(callback: Runnable) {
        drainState.startDraining()
        val sessions = target.drainableSessions()
        log.info { "Draining ${sessions.size} $subject over $window" }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scope.launch {
            try {
                sessions.forEach { session ->
                    launch {
                        delay(Random.nextLong(window.toMillis().coerceAtLeast(1)))
                        session.closeGoingAway()
                    }
                }
                // 지터를 둔 close들과, close 핸드셰이크가 끝날 짧은 유예 시간을 기다린다.
                withTimeoutOrNull(window.toMillis() + GRACE_MILLIS) {
                    while (target.activeCount > 0) delay(POLL_MILLIS)
                }
                log.info { "Drain finished, ${target.activeCount} $subject still open" }
            } finally {
                running = false
                callback.run()
                scope.cancel()
            }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
        const val GRACE_MILLIS = 2_000L
        const val POLL_MILLIS = 50L
    }
}
