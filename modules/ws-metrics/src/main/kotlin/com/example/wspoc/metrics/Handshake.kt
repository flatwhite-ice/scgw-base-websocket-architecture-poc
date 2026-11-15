package com.example.wspoc.metrics

import java.util.concurrent.atomic.AtomicBoolean

/** `ws.handshake`의 `result` 태그 값. */
object HandshakeResult {
    /** 업그레이드 응답(101)이 나갔다. */
    const val OK = "ok"

    /** 401. 사유는 `reason` 태그에 있다. */
    const val UNAUTHORIZED = "unauthorized"

    /** drain 중이라 503으로 거절했다. */
    const val DRAINING = "draining"

    /** 인증은 통과했지만 세션이 열리지 않았다(없는 경로, 업그레이드가 아닌 요청, 업그레이드 실패). */
    const val ERROR = "error"

    /** `unauthorized`가 아닌 결과의 `reason` 태그 값. */
    const val NO_REASON = "none"
}

/**
 * 진행 중인 핸드셰이크 한 건. 결과가 여러 곳에서 정해질 수 있어서(인증 필터의 거절, 업그레이드 완료, 체인 종료)
 * 처음 불린 [record]만 기록한다.
 */
class HandshakeTrace internal constructor(
    private val meters: WsMeters,
    val scope: String,
    private val startNanos: Long,
) {
    private val recorded = AtomicBoolean()

    fun record(
        result: String,
        reason: String = HandshakeResult.NO_REASON,
    ) {
        if (recorded.compareAndSet(false, true)) meters.handshake(scope, result, reason, System.nanoTime() - startNanos)
    }

    companion object {
        /** 진행 중인 핸드셰이크의 trace를 담는 exchange attribute 이름. 필터가 넣고, 업그레이드를 끝낸 쪽이 꺼내 쓴다. */
        const val ATTRIBUTE = "wspoc.handshakeTrace"
    }
}
