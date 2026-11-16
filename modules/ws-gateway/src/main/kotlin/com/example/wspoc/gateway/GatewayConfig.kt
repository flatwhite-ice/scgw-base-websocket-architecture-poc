package com.example.wspoc.gateway

import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.auth.token.RefreshingJwksKeySource
import com.example.wspoc.auth.token.TicketReplayGuard
import com.example.wspoc.gateway.relay.RelaySessionRegistry
import com.example.wspoc.support.drain.DrainCoordinator
import com.example.wspoc.support.drain.DrainState
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 라우트는 application.yml의 `spring.cloud.gateway.server.webflux.routes`에서 정의한다(decisions D15). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties::class)
class GatewayConfig {
    // 빈 값(예: 테스트가 ""로 덮어씀)이면 JWKS를 끈다. @ConditionalOnProperty는 ""에도 매칭된다.
    // 갱신 루프는 컨텍스트가 닫힐 때 멈춘다(close).
    @Bean
    @ConditionalOnExpression("!'\${ws.gateway.auth.jwks-uri:}'.isBlank()")
    fun jwksKeySource(props: GatewayProperties): KeySource =
        RefreshingJwksKeySource(requireNotNull(props.auth.jwksUri), props.auth.jwksRefreshInterval)

    /** 종료 시 켜진다. 그 뒤로 [GatewayEdgeFilter]는 새 업그레이드를 503으로 거절한다(모든 라우트). */
    @Bean
    fun drainState() = DrainState()

    /**
     * relay 계층의 graceful drain. 서비스(ws-backend-core)와 같은 방식이다: relay 중인 모든 클라이언트 세션을
     * `ws.gateway.drain.window` 안의 무작위 시점에 1001로 닫는다. 웹서버 graceful shutdown보다 먼저 동작한다.
     */
    @Bean
    fun drainCoordinator(
        props: GatewayProperties,
        drainState: DrainState,
        registry: RelaySessionRegistry,
    ) = DrainCoordinator(props.drain.window, drainState, registry, subject = "relayed WebSocket sessions")

    /** relay 모드에서 WS 티켓을 소비하는 쪽은 게이트웨이이므로 1회용 검증도 게이트웨이가 맡는다(decisions D4). */
    @Bean
    fun jwtVerifier(keySource: KeySource) = JwtVerifier(keySource, TicketReplayGuard())
}
