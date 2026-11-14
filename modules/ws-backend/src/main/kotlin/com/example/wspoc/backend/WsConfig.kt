package com.example.wspoc.backend

import com.example.wspoc.auth.common.JwksKeySource
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.auth.common.TicketReplayGuard
import com.example.wspoc.backend.session.SessionRegistry
import com.example.wspoc.backend.session.SessionScope
import com.example.wspoc.backend.session.WsSessionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WsProperties::class)
class WsConfig {
    @Bean
    fun echoHandler(
        props: WsProperties,
        registry: SessionRegistry,
        scope: SessionScope,
        jsonMapper: JsonMapper,
    ) = WsSessionHandler(props, registry, scope, jsonMapper, autoSubscribe = false)

    @Bean
    fun pushHandler(
        props: WsProperties,
        registry: SessionRegistry,
        scope: SessionScope,
        jsonMapper: JsonMapper,
    ) = WsSessionHandler(props, registry, scope, jsonMapper, autoSubscribe = true)

    @Bean
    fun wsHandlerMapping(
        echoHandler: WsSessionHandler,
        pushHandler: WsSessionHandler,
    ): HandlerMapping = SimpleUrlHandlerMapping(mapOf("/ws/echo" to echoHandler, "/ws/push" to pushHandler), -1)

    @Bean
    fun webSocketService(props: WsProperties): WebSocketService {
        val strategy =
            ReactorNettyRequestUpgradeStrategy {
                WebsocketServerSpec
                    .builder()
                    .maxFramePayloadLength(props.maxFramePayload.toBytes().toInt())
                    // Let PONG frames reach the session so the heartbeat can see them.
                    .handlePing(false)
            }
        return HandshakeWebSocketService(strategy)
    }

    @Bean
    fun webSocketHandlerAdapter(webSocketService: WebSocketService) = WebSocketHandlerAdapter(webSocketService)

    @Bean
    // Only self mode verifies JWTs. A blank URI (tests override with "") disables JWKS;
    // @ConditionalOnProperty would still match "".
    @ConditionalOnExpression("'\${ws.auth.mode:gateway}'.equalsIgnoreCase('self') and !'\${ws.auth.jwks-uri:}'.isBlank()")
    fun jwksKeySource(props: WsProperties): JwksKeySourceLifecycle = JwksKeySourceLifecycle(props)

    /** Only needed (and only created) when a KeySource exists, i.e. in self mode. Null bean otherwise. */
    @Bean
    fun jwtVerifier(keySource: ObjectProvider<KeySource>): JwtVerifier? =
        keySource.ifAvailable?.let { JwtVerifier(it, TicketReplayGuard()) }
}

/** Owns the JWKS refresh loop for the lifetime of the context. */
class JwksKeySourceLifecycle(
    props: WsProperties,
) : KeySource,
    DisposableBean {
    private val delegate = JwksKeySource(requireNotNull(props.auth.jwksUri), props.auth.jwksRefreshInterval)
    private val scope = CoroutineScope(SupervisorJob()).also { delegate.start(it) }

    override suspend fun find(keyId: String?) = delegate.find(keyId)

    override fun destroy() = scope.cancel()
}
