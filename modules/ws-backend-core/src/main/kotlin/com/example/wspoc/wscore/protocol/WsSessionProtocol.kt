package com.example.wspoc.wscore.protocol

/**
 * 서비스가 구현하는 세션 하나의 메시지 처리. `ManagedWsHandler`가 세션마다 새 인스턴스를 만들므로
 * 세션별 상태(구독 Job 등)를 필드에 둬도 된다. 모든 콜백은 그 세션의 코루틴에서 순서대로 호출된다.
 */
interface WsSessionProtocol {
    /** 세션이 열린 직후(첫 메시지를 받기 전). */
    suspend fun onOpen(session: WsSessionContext) {}

    /** TEXT 프레임 하나. 여기서 suspend하면 그 세션의 수신이 멈춘다(backpressure). */
    suspend fun onText(
        session: WsSessionContext,
        text: String,
    )

    /** 세션이 끝날 때 한 번. 이미 취소 중인 코루틴의 finally에서 호출되므로 suspend하지 않는다. */
    fun onClose(session: WsSessionContext) {}
}
