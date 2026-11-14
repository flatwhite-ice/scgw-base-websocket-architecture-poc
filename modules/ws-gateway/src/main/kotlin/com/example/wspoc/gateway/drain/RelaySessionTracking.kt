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

/**
 * Makes SCG's relayed client sessions visible to the drain.
 *
 * SCG's `WebsocketRoutingFilter` upgrades the client through the `WebSocketService` bean and keeps the resulting
 * session inside its private ProxyWebSocketHandler. That bean is not `@ConditionalOnMissingBean`, so instead of
 * replacing it (or copying SCG's filter) we decorate it: every handler SCG passes in is wrapped so the client
 * session is registered on start and unregistered when the relay ends. Relay behaviour itself is untouched.
 */
@Configuration(proxyBeanMethods = false)
class RelaySessionTracking {
    companion object {
        /** Static so it is registered before other beans; the registry is resolved lazily at wrap time. */
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
    ): Mono<Void> = delegate.handleRequest(exchange, TrackingHandler(handler, registry))

    private class TrackingHandler(
        private val delegate: WebSocketHandler,
        private val registry: RelaySessionRegistry,
    ) : WebSocketHandler {
        // SCG negotiates subprotocols through this; must be passed through unchanged.
        override fun getSubProtocols(): List<String> = delegate.subProtocols

        override fun handle(session: WebSocketSession): Mono<Void> =
            Mono
                .defer {
                    registry.register(session)
                    delegate.handle(session)
                }.doFinally { registry.unregister(session.id) }
    }
}
