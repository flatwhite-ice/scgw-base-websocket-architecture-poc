package com.example.wspoc.wscore

import com.example.wspoc.auth.common.JwksKeySource
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.auth.common.TicketReplayGuard
import com.example.wspoc.wscore.auth.HandshakeAuthenticator
import com.example.wspoc.wscore.auth.WsHandshakeAuthFilter
import com.example.wspoc.wscore.endpoint.WsEndpointHandlerMapping
import com.example.wspoc.wscore.session.DrainCoordinator
import com.example.wspoc.wscore.session.DrainState
import com.example.wspoc.wscore.session.ServerIdentity
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.session.SessionScope
import com.example.wspoc.wscore.session.WsSessionRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec

/**
 * WebSocket 서비스 공통 런타임(D17). 이 모듈을 의존성에 넣으면 등록된다
 * (`META-INF/spring/...AutoConfiguration.imports`). 서비스는 `@WsEndpoint`가 붙은 WebSocketHandler 빈
 * (보통 [com.example.wspoc.wscore.session.ManagedWsHandler])만 선언하면 된다.
 *
 * 등록하는 것: 핸드셰이크 인증 필터(401/503), 세션 레지스트리와 drain(1001), `@WsEndpoint` URL 매핑,
 * Reactor Netty 업그레이드 설정, self 모드의 JWKS 검증기.
 */
@AutoConfiguration
@EnableConfigurationProperties(WsProperties::class)
class WsCoreAutoConfiguration {
    @Bean
    fun drainState() = DrainState()

    @Bean
    fun sessionRegistry() = SessionRegistry()

    @Bean
    fun sessionScope() = SessionScope()

    @Bean
    fun serverIdentity(
        props: WsProperties,
        @Value("\${spring.application.name:ws-app}") appName: String,
    ) = ServerIdentity(props, appName)

    @Bean
    fun wsSessionRuntime(
        props: WsProperties,
        registry: SessionRegistry,
        sessionScope: SessionScope,
        serverIdentity: ServerIdentity,
    ) = WsSessionRuntime(props, registry, sessionScope, serverIdentity)

    @Bean
    fun drainCoordinator(
        props: WsProperties,
        drainState: DrainState,
        registry: SessionRegistry,
        sessionScope: SessionScope,
    ) = DrainCoordinator(props, drainState, registry, sessionScope)

    @Bean
    fun handshakeAuthenticator(
        props: WsProperties,
        verifier: ObjectProvider<JwtVerifier>,
    ) = HandshakeAuthenticator(props, verifier)

    @Bean
    fun wsHandshakeAuthFilter(
        authenticator: HandshakeAuthenticator,
        drainState: DrainState,
    ) = WsHandshakeAuthFilter(authenticator, drainState)

    /** `@WsEndpoint`가 붙은 WebSocketHandler 빈을 모두 모아 경로를 등록한다(D16). */
    @Bean
    fun wsEndpointHandlerMapping(beanFactory: ListableBeanFactory) = WsEndpointHandlerMapping.from(beanFactory)

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
