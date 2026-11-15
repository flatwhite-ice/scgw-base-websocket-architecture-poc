package com.example.wspoc.wscore

import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.http.client.ReactorResourceFactory
import reactor.core.scheduler.Schedulers
import reactor.netty.http.HttpResources
import reactor.netty.resources.LoopResources
import java.util.concurrent.TimeUnit

/**
 * WebSocket 서비스 공통 메트릭(D27). MeterRegistry는 actuator가 만든 것을 쓴다. 없으면(메트릭 자동 구성을 뺀 컨텍스트)
 * 내보내지 않는 임시 registry에 기록해서 런타임이 그대로 돌게 한다.
 */
@AutoConfiguration
class WsMetricsAutoConfiguration {
    /** 세션을 `@WsEndpoint` 선언 경로(`endpoint` 태그)로 묶는다. */
    @Bean
    fun wsMeters(registry: ObjectProvider<MeterRegistry>) = WsMeters(registry.orTemporary(), "endpoint")

    /**
     * `ws.eventloop.lag`. Netty event loop 각각과, 세션 코루틴이 도는 Reactor parallel 스케줄러를 잰다.
     * parallel 쪽은 예약할 때마다 스케줄러가 worker를 돌아가며 고르므로 worker 전체가 표본에 든다.
     */
    @Bean(destroyMethod = "close")
    fun eventLoopLagProbe(
        props: WsProperties,
        registry: ObjectProvider<MeterRegistry>,
        resources: ObjectProvider<ReactorResourceFactory>,
    ): EventLoopLagProbe {
        val probe = EventLoopLagProbe(registry.orTemporary(), props.metrics.eventLoopLagInterval)
        if (props.metrics.eventLoopLagInterval.isZero) return probe
        // Boot의 Netty 서버가 쓰는 event loop다(기본은 전역 HttpResources).
        val serverLoops = (resources.ifAvailable?.loopResources ?: HttpResources.get()).onServer(LoopResources.DEFAULT_NATIVE)
        probe.watch("netty", serverLoops)
        probe.watch("parallel") { task, delay -> Schedulers.parallel().schedule(task, delay, TimeUnit.MILLISECONDS) }
        return probe
    }

    /** Reactor Netty 내장 메트릭. `uri` 태그는 경로 원문 대신 첫 구간만 쓴다([WsMeters.uriTag]). */
    @Bean
    @ConditionalOnProperty("ws.metrics.netty", havingValue = "true", matchIfMissing = true)
    fun nettyMetricsCustomizer() = NettyServerCustomizer { server -> server.metrics(true, WsMeters::uriTag) }

    private fun ObjectProvider<MeterRegistry>.orTemporary(): MeterRegistry = ifAvailable ?: SimpleMeterRegistry()
}
