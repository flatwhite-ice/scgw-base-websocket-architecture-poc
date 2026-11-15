package com.example.wspoc.wscore

import com.example.wspoc.auth.token.JwksKeySource
import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.auth.token.TicketReplayGuard
import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.wscore.auth.HandshakeAuthenticator
import com.example.wspoc.wscore.auth.HandshakeMeteringWebSocketService
import com.example.wspoc.wscore.auth.WsHandshakeAuthFilter
import com.example.wspoc.wscore.endpoint.WsEndpointHandlerMapping
import com.example.wspoc.wscore.session.DrainCoordinator
import com.example.wspoc.wscore.session.DrainState
import com.example.wspoc.wscore.session.ServerIdentity
import com.example.wspoc.wscore.session.SessionRegistry
import com.example.wspoc.wscore.session.SessionScope
import com.example.wspoc.wscore.session.WsSessionRuntime
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.http.client.ReactorResourceFactory
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.core.scheduler.Schedulers
import reactor.netty.http.HttpResources
import reactor.netty.http.server.WebsocketServerSpec
import reactor.netty.resources.LoopResources
import java.util.concurrent.TimeUnit

/**
 * WebSocket 서비스 공통 런타임(D17). 이 모듈을 의존성에 넣으면 등록된다
 * (`META-INF/spring/...AutoConfiguration.imports`). 서비스는 `@WsEndpoint`가 붙은 WebSocketHandler 빈
 * (보통 [com.example.wspoc.wscore.session.ManagedWsHandler])만 선언하면 된다.
 *
 * 등록하는 것: 핸드셰이크 인증 필터(401/503), 세션 레지스트리와 drain(1001), `@WsEndpoint` URL 매핑,
 * Reactor Netty 업그레이드 설정, self 모드의 JWKS 검증기, 공통 메트릭(D27).
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
        drainState: DrainState,
        meters: WsMeters,
    ) = WsSessionRuntime(props, registry, sessionScope, serverIdentity, drainState, meters)

    /**
     * 공통 메트릭(D27). 세션을 `@WsEndpoint` 경로(`endpoint` 태그)로 묶는다. MeterRegistry는 actuator가 만든 것을 쓰고,
     * 없으면(메트릭 자동 구성을 뺀 컨텍스트) 내보내지 않는 임시 registry에 기록해 런타임이 그대로 돌게 한다.
     */
    @Bean
    fun wsMeters(registry: ObjectProvider<MeterRegistry>) = WsMeters(registry.ifAvailable ?: SimpleMeterRegistry(), "endpoint")

    /**
     * `ws.eventloop.lag`. Netty event loop 각각과, 세션 코루틴이 도는 Reactor parallel 스케줄러([SessionScope])를 잰다.
     * parallel 쪽은 예약할 때마다 스케줄러가 worker를 돌아가며 고르므로 worker 전체가 표본에 든다.
     */
    @Bean(destroyMethod = "close")
    fun eventLoopLagProbe(
        props: WsProperties,
        registry: ObjectProvider<MeterRegistry>,
        resources: ObjectProvider<ReactorResourceFactory>,
    ): EventLoopLagProbe {
        val probe = EventLoopLagProbe(registry.ifAvailable ?: SimpleMeterRegistry(), props.metrics.eventLoopLagInterval)
        if (props.metrics.eventLoopLagInterval.isZero) return probe
        // Boot의 Netty 서버가 쓰는 event loop다(기본은 전역 HttpResources).
        val loops = (resources.ifAvailable?.loopResources ?: HttpResources.get()).onServer(LoopResources.DEFAULT_NATIVE)
        loops.forEach { loop -> probe.watch("netty") { task, delay -> loop.schedule(task, delay, TimeUnit.MILLISECONDS) } }
        probe.watch("parallel") { task, delay -> Schedulers.parallel().schedule(task, delay, TimeUnit.MILLISECONDS) }
        return probe
    }

    /** Reactor Netty 내장 메트릭. `uri` 태그는 경로 원문 대신 첫 구간만 쓴다([WsMeters.uriTag]). */
    @Bean
    @ConditionalOnProperty("ws.metrics.netty", havingValue = "true", matchIfMissing = true)
    fun nettyMetricsCustomizer() = NettyServerCustomizer { server -> server.metrics(true, WsMeters::uriTag) }

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
        endpoints: WsEndpointHandlerMapping,
        meters: WsMeters,
    ) = WsHandshakeAuthFilter(authenticator, drainState, endpoints, meters)

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
        return HandshakeMeteringWebSocketService(HandshakeWebSocketService(strategy))
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
