package com.example.wspoc.backend

import com.example.wspoc.auth.common.JwksKeySource
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.auth.common.TicketReplayGuard
import com.example.wspoc.backend.session.ServerIdentity
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
        serverIdentity: ServerIdentity,
    ) = WsSessionHandler(props, registry, scope, jsonMapper, serverIdentity, autoSubscribe = false)

    @Bean
    fun pushHandler(
        props: WsProperties,
        registry: SessionRegistry,
        scope: SessionScope,
        jsonMapper: JsonMapper,
        serverIdentity: ServerIdentity,
    ) = WsSessionHandler(props, registry, scope, jsonMapper, serverIdentity, autoSubscribe = true)

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
                    // PONG 프레임이 세션까지 전달되게 해서 heartbeat가 볼 수 있게 한다.
                    .handlePing(false)
            }
        return HandshakeWebSocketService(strategy)
    }

    @Bean
    fun webSocketHandlerAdapter(webSocketService: WebSocketService) = WebSocketHandlerAdapter(webSocketService)

    @Bean
    // JWT를 검증하는 것은 self 모드뿐이다. URI가 비어 있으면(테스트가 ""로 덮어씀) JWKS를 끈다.
    // @ConditionalOnProperty는 ""에도 매칭되므로 쓰지 않는다.
    @ConditionalOnExpression("'\${ws.auth.mode:gateway}'.equalsIgnoreCase('self') and !'\${ws.auth.jwks-uri:}'.isBlank()")
    fun jwksKeySource(props: WsProperties): JwksKeySourceLifecycle = JwksKeySourceLifecycle(props)

    /** KeySource가 있을 때(즉 self 모드)만 필요하고 그때만 만든다. 그 외에는 null 빈이다. */
    @Bean
    fun jwtVerifier(keySource: ObjectProvider<KeySource>): JwtVerifier? =
        keySource.ifAvailable?.let { JwtVerifier(it, TicketReplayGuard()) }
}

/** 컨텍스트가 살아 있는 동안 JWKS 갱신 루프를 관리한다. */
class JwksKeySourceLifecycle(
    props: WsProperties,
) : KeySource,
    DisposableBean {
    private val delegate = JwksKeySource(requireNotNull(props.auth.jwksUri), props.auth.jwksRefreshInterval)
    private val scope = CoroutineScope(SupervisorJob()).also { delegate.start(it) }

    override suspend fun find(keyId: String?) = delegate.find(keyId)

    override fun destroy() = scope.cancel()
}
