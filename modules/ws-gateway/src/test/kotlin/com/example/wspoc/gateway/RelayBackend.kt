package com.example.wspoc.gateway

import com.example.wspoc.backend.WsBackendApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.web.server.WebFilter
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** What the backend actually received for a `/ws/` handshake (after the gateway rewrote it). */
data class SeenHandshake(
    val uri: URI,
    val headers: HttpHeaders,
)

/**
 * A real ws-backend (gateway auth mode) started once per JVM on a random port, standing behind the gateway
 * under test. It records every `/ws/` request it receives so tests can assert what crossed the relay hop.
 */
object RelayBackend {
    val seen = CopyOnWriteArrayList<SeenHandshake>()

    private val context: ConfigurableApplicationContext by lazy {
        SpringApplicationBuilder(WsBackendApplication::class.java, RecorderConfig::class.java)
            .properties(
                // Do not pick up the gateway's application.yml from the shared test classpath.
                "spring.config.name=ws-backend-e2e",
                "spring.application.name=ws-backend",
                "server.port=0",
                "ws.auth.mode=gateway",
                "ws.heartbeat.interval=30s",
                // The gateway starter is on this classpath too; the backend must not act as a gateway.
                "spring.cloud.gateway.server.webflux.enabled=false",
                "spring.cloud.discovery.enabled=false",
            ).run()
    }

    val port: Int get() = requireNotNull((context as WebServerApplicationContext).webServer).port

    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /** @TestConfiguration keeps it out of the gateway context's component scan (same package). */
    @TestConfiguration(proxyBeanMethods = false)
    class RecorderConfig {
        @Bean
        fun handshakeRecorder() =
            WebFilter { exchange, chain ->
                if (exchange.request.path
                        .value()
                        .startsWith("/ws/")
                ) {
                    seen += SeenHandshake(exchange.request.uri, HttpHeaders.readOnlyHttpHeaders(exchange.request.headers))
                }
                chain.filter(exchange)
            }
    }
}
