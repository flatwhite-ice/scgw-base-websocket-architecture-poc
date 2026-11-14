package com.example.wspoc.wscore.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import reactor.core.scheduler.Schedulers

/**
 * 세션별 코루틴(타이머, push 루프)용 dispatcher. Reactor parallel 스케줄러 기반이라 `delay()`가 kotlinx의
 * 단일 DefaultExecutor 스레드 대신 모든 코어의 Reactor 타이머를 쓴다. 또한 이 스레드들은 non-blocking
 * 스레드라 BlockHound 검사 대상이다.
 */
class SessionScope {
    val dispatcher: CoroutineDispatcher = Schedulers.parallel().asCoroutineDispatcher()
}
