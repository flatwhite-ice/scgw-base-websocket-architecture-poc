package com.example.wspoc.wscore.protocol

import com.example.wspoc.auth.token.AuthPrincipal
import kotlinx.coroutines.CoroutineScope

/** 프로토콜이 보는 세션. 송신 큐와 수명은 `ManagedWsHandler`가 관리한다. */
interface WsSessionContext {
    val id: String

    /** 인증된 사용자. [anonymous]이면 자리표시자(`userId = "anonymous"`)다. */
    val principal: AuthPrincipal

    /** `@WsEndpoint(auth = NONE)` 엔드포인트의 익명 세션인가. */
    val anonymous: Boolean

    /** 이 인스턴스의 식별자(응답의 `server` 필드). */
    val serverId: String

    /** 세션 수명과 같은 scope. 여기서 띄운 코루틴은 세션이 끝나면 취소된다. */
    val scope: CoroutineScope

    /** 응답용: 큐가 차면 기다린다(수신 루프에 backpressure). 닫히는 중이면 조용히 버린다. */
    suspend fun send(text: String)

    /** push/broadcast용: 큐가 차면 기다리지 않고 버린다(느린 소비자). 다른 세션의 코루틴에서 불러도 된다. */
    fun trySend(text: String): Offer

    enum class Offer { SENT, DROPPED, CLOSED }
}
