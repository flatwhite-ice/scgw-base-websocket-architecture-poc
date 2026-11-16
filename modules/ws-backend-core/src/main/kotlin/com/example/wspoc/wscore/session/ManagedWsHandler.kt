package com.example.wspoc.wscore.session

import com.example.wspoc.wscore.auth.WsPrincipal
import com.example.wspoc.wscore.protocol.WsSessionProtocol
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * 서비스가 정의하는 메시지 처리([WsSessionProtocol])에 세션 수명 관리를 붙이는 WebSocketHandler.
 * 엔드포인트마다 하나를 `@WsEndpoint` 빈으로 등록한다. 세션마다 [protocolFactory]로 프로토콜을 새로 만들고,
 * 그 세션의 수명(heartbeat, 토큰 만료, drain, 송신 큐, 메트릭)은 [ManagedSession]이 맡는다.
 *
 * 세션은 [SessionScope.dispatcher]의 코루틴 하나로 실행된다.
 */
class ManagedWsHandler(
    private val runtime: WsSessionRuntime,
    private val protocolFactory: () -> WsSessionProtocol,
) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> =
        mono(runtime.sessionScope.dispatcher) {
            val principal = session.handshakeInfo.principal.awaitSingleOrNull() as? WsPrincipal
            if (principal == null) {
                // 실제로는 도달하지 않는다: WsHandshakeAuthFilter가 미인증 핸드셰이크를 업그레이드 전에 거절한다.
                session.close(CloseCodes.POLICY_VIOLATION).awaitSingleOrNull()
            } else {
                ManagedSession(session, principal, protocolFactory(), runtime).run()
            }
        }.then()
}
