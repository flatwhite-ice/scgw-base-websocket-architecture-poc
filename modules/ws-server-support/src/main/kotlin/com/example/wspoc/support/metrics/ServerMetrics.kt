package com.example.wspoc.support.metrics

import com.example.wspoc.metrics.EventLoopLagProbe
import com.example.wspoc.metrics.WsMeters
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.http.client.ReactorResourceFactory
import reactor.netty.http.HttpResources
import reactor.netty.resources.LoopResources
import java.time.Duration

/** 서비스와 게이트웨이가 같은 방식으로 구성하는 서버 메트릭(D27). 빈 등록은 각 모듈의 설정 클래스가 한다. */
object ServerMetrics {
    /** [EventLoopLagProbe.watch]의 `pool` 태그 값: Netty 서버 event loop. */
    const val NETTY_POOL = "netty"

    /**
     * `ws.eventloop.lag`를 재는 probe. Boot의 Netty 서버가 쓰는 event loop 각각을 지켜본다
     * ([resources]가 없으면 전역 HttpResources). [interval]이 0이면 아무것도 지켜보지 않는다.
     * 다른 풀도 재려면 돌려받은 probe에 `watch`를 더 부른다.
     */
    fun eventLoopLagProbe(
        registry: MeterRegistry,
        interval: Duration,
        resources: ReactorResourceFactory?,
    ): EventLoopLagProbe {
        val probe = EventLoopLagProbe(registry, interval)
        if (interval.isZero) return probe
        val serverLoops = (resources?.loopResources ?: HttpResources.get()).onServer(LoopResources.DEFAULT_NATIVE)
        probe.watch(NETTY_POOL, serverLoops)
        return probe
    }

    /** Reactor Netty 내장 메트릭(서버 쪽). `uri` 태그는 경로 원문 대신 첫 구간만 쓴다([WsMeters.uriTag]). */
    fun nettyServerMetrics() = NettyServerCustomizer { server -> server.metrics(true, WsMeters::uriTag) }
}
