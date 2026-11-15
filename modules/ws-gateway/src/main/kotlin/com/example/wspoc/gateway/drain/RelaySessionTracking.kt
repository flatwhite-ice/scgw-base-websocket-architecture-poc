package com.example.wspoc.gateway.drain

import com.example.wspoc.gateway.GatewayEdgeFilter
import com.example.wspoc.metrics.HandshakeResult
import com.example.wspoc.metrics.WsMeters
import org.reactivestreams.Publisher
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.WebSocketClient
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SCG가 relay 중인 클라이언트 세션을 drain에서 볼 수 있게 한다.
 *
 * SCG의 `WebsocketRoutingFilter`는 `WebSocketService` 빈으로 클라이언트를 업그레이드하고, 만들어진 세션은
 * private ProxyWebSocketHandler 안에 둔다. 이 빈은 `@ConditionalOnMissingBean`이 아니므로 교체하거나
 * (SCG 필터를 복사하지) 않고 감싼다: SCG가 넘기는 핸들러를 모두 래핑해서, 클라이언트 세션을 시작 시 등록하고
 * relay가 끝나면 해제한다. relay 동작 자체는 건드리지 않는다.
 *
 * 예외는 drain 중에 들어온 세션 하나다: GatewayEdgeFilter의 503 검사를 통과한 뒤 drain이 시작되면
 * GatewayDrainCoordinator의 snapshot에 없을 수 있으므로, relay를 시작하지 않고(upstream 연결 없음) 바로 1001로 닫는다.
 *
 * 메트릭도 같은 자리에서 잰다(D27): 라우트별 활성 세션, 클라이언트 쪽 데이터 프레임 수, close 코드, 세션 수명.
 * upstream 연결은 SCG가 쓰는 `WebSocketClient` 빈을 같은 방식으로 감싸서 잰다([TrackingWebSocketClient]).
 */
@Configuration(proxyBeanMethods = false)
class RelaySessionTracking {
    companion object {
        /** 다른 빈보다 먼저 등록되도록 static으로 둔다. registry는 래핑 시점에 지연 조회한다. */
        @JvmStatic
        @Bean
        fun relaySessionTrackingPostProcessor(
            registry: ObjectProvider<RelaySessionRegistry>,
            drainState: ObjectProvider<GatewayDrainState>,
            meters: ObjectProvider<WsMeters>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when (bean) {
                        is WebSocketService ->
                            TrackingWebSocketService(bean, registry.getObject(), drainState.getObject(), meters.getObject())
                        is WebSocketClient -> TrackingWebSocketClient(bean, meters.getObject())
                        else -> bean
                    }
            }

        /** upstream 연결 측정에 쓸 라우트 id를 넘기는 Reactor context 키. */
        const val ROUTE_CONTEXT_KEY = "wspoc.route"

        /** 상대가 닫은 경우 close 프레임 상태를 기다리는 최대 시간(메트릭과 로그용). */
        val CLOSE_STATUS_WAIT: Duration = Duration.ofSeconds(1)
    }
}

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
        return delegate
            .handleRequest(exchange, TrackingHandler(handler, registry, drainState, context, meters.scope(context.routeId)))
            // 클라이언트 쪽 업그레이드(101)가 끝나면 완료된다. upstream 연결은 이 뒤의 일이라 따로 잰다.
            .doOnSuccess { GatewayEdgeFilter.handshakeTrace(exchange)?.record(HandshakeResult.OK) }
    }

    private class TrackingHandler(
        private val delegate: WebSocketHandler,
        private val registry: RelaySessionRegistry,
        private val drainState: GatewayDrainState,
        private val context: RelayLogContext,
        private val meters: WsMeters.Scope,
    ) : WebSocketHandler {
        // SCG는 이것으로 subprotocol을 협상하므로 그대로 넘겨야 한다.
        override fun getSubProtocols(): List<String> = delegate.subProtocols

        override fun handle(session: WebSocketSession): Mono<Void> {
            val openedAt = System.nanoTime()
            return Mono
                .defer {
                    registry.register(session)
                    meters.sessionOpened()
                    RelaySessionLog.opened(session, context, registry.activeCount)
                    // 등록한 뒤에 확인한다: drain이 먼저 켜졌다면 여기서 닫고, 아니라면 coordinator의 snapshot에 들어간다.
                    if (drainState.draining) {
                        session.close(CloseStatus.GOING_AWAY)
                    } else {
                        delegate
                            .handle(CountingSession(session, meters))
                            .contextWrite { it.put(RelaySessionTracking.ROUTE_CONTEXT_KEY, context.routeId) }
                    }
                }.doFinally {
                    registry.unregister(session.id)
                    val duration = Duration.ofNanos(System.nanoTime() - openedAt)
                    meters.sessionEnded(duration.toNanos())
                    val active = registry.activeCount
                    // close 프레임 없이 끊기면 1005(No Status)로 남긴다.
                    session
                        .closeStatus()
                        .timeout(RelaySessionTracking.CLOSE_STATUS_WAIT)
                        .onErrorResume { Mono.empty() }
                        .defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
                        .subscribe { status ->
                            meters.closed(status.code)
                            RelaySessionLog.closed(session, context, status, duration, active)
                        }
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
}

/**
 * `ws.relay.upstream.connect`: SCG가 upstream으로 WebSocket을 여는 데 걸린 시간과 결과.
 * SCG는 클라이언트에 101을 먼저 응답하고 upstream을 나중에 열기 때문에(D14), upstream 연결 실패는 핸드셰이크 결과가 아니라
 * 여기에 `result=error`로 나타난다(클라이언트는 "101 뒤 1002"를 본다). 포트 고갈(E3)과 죽은 backend(E4)를 볼 때 쓴다.
 *
 * 라우트 id는 [TrackingWebSocketService]가 Reactor context에 넣어 준다. SCG의 프록시 핸들러가 `client.execute(...)`의
 * Mono를 그대로 돌려주므로 context가 여기까지 전달된다.
 */
class TrackingWebSocketClient(
    private val delegate: WebSocketClient,
    private val meters: WsMeters,
) : WebSocketClient {
    override fun execute(
        url: URI,
        handler: WebSocketHandler,
    ): Mono<Void> = execute(url, HttpHeaders(), handler)

    override fun execute(
        url: URI,
        headers: HttpHeaders,
        handler: WebSocketHandler,
    ): Mono<Void> =
        Mono.deferContextual { context ->
            val route = context.getOrDefault(RelaySessionTracking.ROUTE_CONTEXT_KEY, WsMeters.UNKNOWN_SCOPE)!!
            val startedAt = System.nanoTime()
            val settled = AtomicBoolean()
            val connected =
                object : WebSocketHandler {
                    override fun getSubProtocols(): List<String> = handler.subProtocols

                    override fun handle(session: WebSocketSession): Mono<Void> {
                        if (settled.compareAndSet(false, true)) meters.upstreamConnect(route, null, System.nanoTime() - startedAt)
                        return handler.handle(session)
                    }
                }
            // 핸들러가 불리기 전의 오류만 연결 실패다. 연결된 뒤의 오류는 relay 도중의 일이라 세지 않는다.
            delegate.execute(url, headers, connected).doOnError { error ->
                if (settled.compareAndSet(false, true)) meters.upstreamConnect(route, error, System.nanoTime() - startedAt)
            }
        }
}
