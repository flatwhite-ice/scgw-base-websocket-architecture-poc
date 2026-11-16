package com.example.wspoc.support.session

import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Duration

/** 상대가 닫은 경우 close 프레임 상태를 기다리는 최대 시간(메트릭과 로그용). */
private val CLOSE_STATUS_WAIT: Duration = Duration.ofSeconds(1)

/**
 * 끝난 세션의 close 상태. 상태는 연결이 끝난 뒤 조금 늦게 올 수 있어 잠깐 기다리고,
 * close 프레임 없이 끊겼으면 1005(No Status)를 돌려준다. 항상 값 하나로 끝난다.
 */
fun WebSocketSession.closeStatusOrNoStatus(): Mono<CloseStatus> =
    closeStatus()
        .timeout(CLOSE_STATUS_WAIT)
        .onErrorResume { Mono.empty() }
        .defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
