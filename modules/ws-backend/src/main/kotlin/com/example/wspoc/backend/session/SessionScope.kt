package com.example.wspoc.backend.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import org.springframework.stereotype.Component
import reactor.core.scheduler.Schedulers

/**
 * Dispatcher for per-session coroutines (timers, push loops). Backed by Reactor's parallel scheduler so that
 * `delay()` uses Reactor timers across all cores instead of kotlinx's single DefaultExecutor thread, and the
 * threads are non-blocking (BlockHound-enforced).
 */
@Component
class SessionScope {
    val dispatcher: CoroutineDispatcher = Schedulers.parallel().asCoroutineDispatcher()
}
