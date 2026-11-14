package com.example.wspoc.gateway.drain

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * SCG가 relay 중인 클라이언트 세션을 drain에서 볼 수 있게 한다.
 *
 * SCG의 `WebsocketRoutingFilter`는 `WebSocketService` 빈으로 클라이언트를 업그레이드하고, 만들어진 세션은
 * private ProxyWebSocketHandler 안에 둔다. 이 빈은 `@ConditionalOnMissingBean`이 아니므로 교체하거나
 * (SCG 필터를 복사하지) 않고 감싼다: SCG가 넘기는 핸들러를 모두 래핑해서, 클라이언트 세션을 시작 시 등록하고
 * relay가 끝나면 해제한다. relay 동작 자체는 건드리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class RelaySessionTracking {
    companion object {
        /** 다른 빈보다 먼저 등록되도록 static으로 둔다. registry는 래핑 시점에 지연 조회한다. */
        @JvmStatic
        @Bean
        fun relaySessionTrackingPostProcessor(registry: ObjectProvider<RelaySessionRegistry>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any = if (bean is WebSocketService) TrackingWebSocketService(bean, registry.getObject()) else bean
            }
    }
}

class TrackingWebSocketService(
    private val delegate: WebSocketService,
    private val registry: RelaySessionRegistry,
) : WebSocketService {
    override fun handleRequest(
        exchange: ServerWebExchange,
        handler: WebSocketHandler,
    ): Mono<Void> = delegate.handleRequest(exchange, TrackingHandler(handler, registry, RelayLogContext.of(exchange)))

    private class TrackingHandler(
        private val delegate: WebSocketHandler,
        private val registry: RelaySessionRegistry,
        private val context: RelayLogContext,
    ) : WebSocketHandler {
        // SCG는 이것으로 subprotocol을 협상하므로 그대로 넘겨야 한다.
        override fun getSubProtocols(): List<String> = delegate.subProtocols

        override fun handle(session: WebSocketSession): Mono<Void> {
            val openedAt = System.nanoTime()
            return Mono
                .defer {
                    registry.register(session)
                    RelaySessionLog.opened(session, context, registry.activeCount)
                    delegate.handle(session)
                }.doFinally {
                    registry.unregister(session.id)
                    RelaySessionLog.closed(session, context, Duration.ofNanos(System.nanoTime() - openedAt), registry.activeCount)
                }
        }
    }
}
