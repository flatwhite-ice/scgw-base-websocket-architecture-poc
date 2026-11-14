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
        /** `lb:ws://<service-id>` via Spring Cloud LoadBalancer: one upstream destination per backend pod IP. */
        DISCOVERY,

        /** A single fixed URI (e.g. a K8s ClusterIP VIP). Used by experiment E3 to reproduce port exhaustion. */
        STATIC,
    }

    data class Backend(
        val mode: BackendMode = BackendMode.DISCOVERY,
        val serviceId: String = "ws-backend",
        val uri: URI = URI.create("ws://localhost:8081"),
    )

    data class Drain(
        /** Relayed sessions are closed with 1001 at random points inside this window on shutdown. */
        val window: Duration = Duration.ofSeconds(20),
    )

    data class Auth(
        val jwksUri: URI? = null,
        val jwksRefreshInterval: Duration = Duration.ofMinutes(5),
    )
}
