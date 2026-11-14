package com.example.wspoc.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("ws.gateway")
data class GatewayProperties(
    val auth: Auth = Auth(),
    val drain: Drain = Drain(),
) {
    data class Drain(
        /** 종료 시 relay 세션을 이 window 안의 무작위 시점에 1001로 닫는다. */
        val window: Duration = Duration.ofSeconds(20),
    )

    data class Auth(
        val jwksUri: URI? = null,
        val jwksRefreshInterval: Duration = Duration.ofMinutes(5),
    )
}
