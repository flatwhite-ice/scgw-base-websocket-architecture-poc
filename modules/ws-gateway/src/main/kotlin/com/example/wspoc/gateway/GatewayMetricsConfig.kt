package com.example.wspoc.gateway

import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.cloud.gateway.config.HttpClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ReactorResourceFactory
import reactor.netty.http.HttpResources
import reactor.netty.resources.LoopResources
import java.util.concurrent.TimeUnit

/**
 * 게이트웨이 메트릭(D27). 이름은 서비스(ws-backend-core)와 같고 `application` 태그로 구분한다.
 * 세션은 라우트 id(`route` 태그)로 묶는다. 요청 경로는 태그로 쓰지 않는다: 라우트를 넓게 잡으면 값이 끝없이 늘어난다.
 */
@Configuration(proxyBeanMethods = false)
class GatewayMetricsConfig {
    @Bean
    fun wsMeters(registry: MeterRegistry) = WsMeters(registry, "route")

    /** `ws.eventloop.lag`. 게이트웨이의 필터와 relay는 모두 Netty event loop에서 돈다(서버와 upstream 클라이언트가 같은 loop를 쓴다). */
    @Bean(destroyMethod = "close")
    fun eventLoopLagProbe(
        props: GatewayProperties,
        registry: MeterRegistry,
        resources: ObjectProvider<ReactorResourceFactory>,
    ): EventLoopLagProbe {
        val probe = EventLoopLagProbe(registry, props.metrics.eventLoopLagInterval)
        if (props.metrics.eventLoopLagInterval.isZero) return probe
        val loops = (resources.ifAvailable?.loopResources ?: HttpResources.get()).onServer(LoopResources.DEFAULT_NATIVE)
        loops.forEach { loop -> probe.watch("netty") { task, delay -> loop.schedule(task, delay, TimeUnit.MILLISECONDS) } }
        return probe
    }

    /** Reactor Netty 내장 메트릭(서버 쪽). `uri` 태그는 경로 원문 대신 첫 구간만 쓴다. */
    @Bean
    @ConditionalOnProperty("ws.gateway.metrics.netty", havingValue = "true", matchIfMissing = true)
    fun nettyServerMetricsCustomizer() = NettyServerCustomizer { server -> server.metrics(true, WsMeters::uriTag) }

    /** Reactor Netty 내장 메트릭(upstream 클라이언트 쪽). 커넥션 풀 메트릭은 yml의 `httpclient.pool.metrics`로 켠다. */
    @Bean
    @ConditionalOnProperty("ws.gateway.metrics.netty", havingValue = "true", matchIfMissing = true)
    fun nettyClientMetricsCustomizer() = HttpClientCustomizer { client -> client.metrics(true, WsMeters::uriTag) }
}
