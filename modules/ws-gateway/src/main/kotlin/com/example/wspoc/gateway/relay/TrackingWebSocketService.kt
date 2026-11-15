package com.example.wspoc.gateway.relay

import com.example.wspoc.metrics.HandshakeResult
import com.example.wspoc.metrics.HandshakeTrace
import com.example.wspoc.metrics.WsMeters
import org.reactivestreams.Publisher
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * SCG의 `WebSocketService`를 감싸 클라이언트 쪽 세션을 추적한다. SCG가 넘기는 relay 핸들러를 [RelayHandler]로 감싸는 것이 전부다.
 * 클라이언트 쪽 업그레이드(101)가 끝나면 `ws.handshake`를 `ok`로 기록한다. upstream 연결은 그 뒤의 일이라 따로 잰다(D14, D27).
 */
class TrackingWebSocketService(
    private val delegate: WebSocketService,
    private val registry: RelaySessionRegistry,
    private val drainState: GatewayDrainState,
    private val meters: WsMeters,
) : WebSocketService {
    override fun handleRequest(
        exchange: ServerWebExchange,
        handler: WebSocketHandler,
    ): Mono<Void> {
        val context = RelayLogContext.of(exchange)
        val tracked = RelayHandler(handler, context, meters.scope(context.routeId))
        return delegate
            .handleRequest(exchange, tracked)
            .doOnSuccess { exchange.getAttribute<HandshakeTrace>(HandshakeTrace.ATTRIBUTE)?.record(HandshakeResult.OK) }
    }

    /** relay 세션 하나: 등록하고, relay를 SCG에 맡기고, 끝나면 해제하며 메트릭과 로그를 남긴다. */
    private inner class RelayHandler(
        private val relay: WebSocketHandler,
        private val context: RelayLogContext,
        private val meters: WsMeters.Scope,
    ) : WebSocketHandler {
        // SCG는 이것으로 subprotocol을 협상하므로 그대로 넘겨야 한다.
        override fun getSubProtocols(): List<String> = relay.subProtocols

        override fun handle(session: WebSocketSession): Mono<Void> {
            val openedAt = System.nanoTime()
            return Mono
                .defer {
                    opened(session)
                    // 등록한 뒤에 확인한다: 503 검사 뒤에 drain이 시작됐다면 coordinator의 snapshot에 없을 수 있으므로,
                    // upstream을 열지 않고 여기서 바로 1001로 닫는다(D24).
                    if (drainState.draining) session.close(CloseStatus.GOING_AWAY) else relay(session)
                }.doFinally { ended(session, Duration.ofNanos(System.nanoTime() - openedAt)) }
        }

        private fun relay(session: WebSocketSession): Mono<Void> =
            relay
                .handle(CountingSession(session, meters))
                .contextWrite { it.put(RelaySessionTracking.ROUTE_CONTEXT_KEY, context.routeId) }

        private fun opened(session: WebSocketSession) {
            registry.register(session)
            meters.sessionOpened()
            RelaySessionLog.opened(session, context, registry.activeCount)
        }

        private fun ended(
            session: WebSocketSession,
            duration: Duration,
        ) {
            registry.unregister(session.id)
            meters.sessionEnded(duration.toNanos())
            val active = registry.activeCount
            // close 상태는 조금 늦게 올 수 있어 기다리지 않고 구독한다. close 프레임 없이 끊기면 1005(No Status)로 남긴다.
            session
                .closeStatus()
                .timeout(CLOSE_STATUS_WAIT)
                .onErrorResume { Mono.empty() }
                .defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
                .subscribe { status ->
                    meters.closed(status.code)
                    RelaySessionLog.closed(session, context, status, duration, active)
                }
        }
    }

    /**
     * 클라이언트 쪽 세션을 지나가는 데이터 프레임(TEXT/BINARY)을 센다. SCG의 relay는 이 세션의 `receive()`와 `send()`만 쓴다.
     * 프레임 내용은 건드리지 않고 Counter만 올린다.
     */
    private class CountingSession(
        private val delegate: WebSocketSession,
        private val meters: WsMeters.Scope,
    ) : WebSocketSession by delegate {
        override fun receive(): Flux<WebSocketMessage> = delegate.receive().doOnNext { if (it.isData()) meters.messagesIn.increment() }

        override fun send(messages: Publisher<WebSocketMessage>): Mono<Void> =
            delegate.send(Flux.from(messages).doOnNext { if (it.isData()) meters.messagesOut.increment() })

        private fun WebSocketMessage.isData() = type == WebSocketMessage.Type.TEXT || type == WebSocketMessage.Type.BINARY
    }

    private companion object {
        /** 상대가 닫은 경우 close 프레임 상태를 기다리는 최대 시간(메트릭과 로그용). */
        val CLOSE_STATUS_WAIT: Duration = Duration.ofSeconds(1)
    }
}
