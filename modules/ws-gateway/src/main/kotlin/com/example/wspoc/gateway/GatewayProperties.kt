package com.example.wspoc.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("ws.gateway")
data class GatewayProperties(
    val auth: Auth = Auth(),
    val drain: Drain = Drain(),
    val metrics: Metrics = Metrics(),
) {
    data class Metrics(
        /**
         * Reactor Netty 내장 메트릭(서버와 upstream 클라이언트의 연결 수, 송수신 바이트, ByteBuf 할당자, event loop 대기 작업)을 켠다.
         * 연결마다 계측 핸들러가 붙으므로, 그 비용까지 빼고 재려는 실험에서는 끈다.
         * upstream 커넥션 풀 메트릭은 이 값과 별개로 yml의 `httpclient.pool.metrics`로 켜고 끈다.
         */
        val netty: Boolean = true,
        /** `ws.eventloop.lag` 측정 주기. 0이면 측정하지 않는다. */
        val eventLoopLagInterval: Duration = Duration.ofMillis(100),
    )

    data class Drain(
        /** 종료 시 relay 세션을 이 window 안의 무작위 시점에 1001로 닫는다. */
        val window: Duration = Duration.ofSeconds(20),
    )

    data class Auth(
        val jwksUri: URI? = null,
        val jwksRefreshInterval: Duration = Duration.ofMinutes(5),
    )
}
