package com.example.wspoc.gateway

import com.example.wspoc.auth.common.JwksKeySource
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.auth.common.TicketReplayGuard
import com.example.wspoc.gateway.GatewayProperties.BackendMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties::class)
class GatewayConfig {
    @Bean
    fun wsRoutes(
        builder: RouteLocatorBuilder,
        props: GatewayProperties,
    ): RouteLocator {
        val target =
            when (props.backend.mode) {
                BackendMode.DISCOVERY -> "lb:ws://${props.backend.serviceId}"
                BackendMode.STATIC -> props.backend.uri.toString()
            }
        return builder
            .routes()
            .route("ws-backend") { it.path("/ws/**").uri(target) }
            .build()
    }

    @Bean
    // 빈 값(예: 테스트가 ""로 덮어씀)이면 JWKS를 끈다. @ConditionalOnProperty는 ""에도 매칭된다.
    @ConditionalOnExpression("!'\${ws.gateway.auth.jwks-uri:}'.isBlank()")
    fun jwksKeySource(props: GatewayProperties): KeySource = JwksKeySourceLifecycle(props)

    /** relay 모드에서 WS 티켓을 소비하는 쪽은 게이트웨이이므로 1회용 검증도 게이트웨이가 맡는다(decisions D4). */
    @Bean
    fun jwtVerifier(keySource: KeySource) = JwtVerifier(keySource, TicketReplayGuard())
}

/** 컨텍스트가 살아 있는 동안 JWKS 갱신 루프를 관리한다. */
class JwksKeySourceLifecycle(
    props: GatewayProperties,
) : KeySource,
    DisposableBean {
    private val delegate = JwksKeySource(requireNotNull(props.auth.jwksUri), props.auth.jwksRefreshInterval)
    private val scope = CoroutineScope(SupervisorJob()).also { delegate.start(it) }

    override suspend fun find(keyId: String?) = delegate.find(keyId)

    override fun destroy() = scope.cancel()
}
