package com.example.wspoc.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("ws.gateway")
data class GatewayProperties(
    val backend: Backend = Backend(),
    val auth: Auth = Auth(),
    val drain: Drain = Drain(),
) {
    enum class BackendMode {
        /** Spring Cloud LoadBalancer로 `lb:ws://<service-id>`: backend 파드 IP마다 upstream 목적지가 하나씩. */
        DISCOVERY,

        /** 고정 URI 하나(예: K8s ClusterIP VIP). 실험 E3에서 포트 고갈을 재현할 때 쓴다. */
        STATIC,
    }

    data class Backend(
        val mode: BackendMode = BackendMode.DISCOVERY,
        val serviceId: String = "ws-backend",
        val uri: URI = URI.create("ws://localhost:8081"),
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
