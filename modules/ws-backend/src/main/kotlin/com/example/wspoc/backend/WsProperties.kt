package com.example.wspoc.backend

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

@ConfigurationProperties("ws")
data class WsProperties(
    val auth: Auth = Auth(),
    val heartbeat: Heartbeat = Heartbeat(),
    val drain: Drain = Drain(),
    val push: Push = Push(),
    val maxFramePayload: DataSize = DataSize.ofKilobytes(64),
    /** Per-session outbound queue. When full, push messages are dropped (and counted) instead of buffering without bound. */
    val outboundBufferSize: Int = 256,
) {
    enum class AuthMode {
        /** Relay: trust X-User-Id / X-Scopes / X-Auth-Exp injected by ws-gateway. */
        GATEWAY,

        /** Direct: verify `?ticket=` or `Authorization: Bearer` during the handshake. */
        SELF,
    }

    data class Auth(
        val mode: AuthMode = AuthMode.GATEWAY,
        /** JWKS of auth-api. Required in SELF mode (set by the `direct` profile) unless another KeySource bean exists. */
        val jwksUri: URI? = null,
        val jwksRefreshInterval: Duration = Duration.ofMinutes(5),
    )

    data class Heartbeat(
        val interval: Duration = Duration.ofSeconds(30),
        val maxMissed: Int = 2,
    )

    data class Drain(
        val window: Duration = Duration.ofSeconds(20),
    )

    data class Push(
        val defaultTopic: String = "ticker",
        val defaultIntervalMs: Long = 1000,
        val defaultPayloadBytes: Int = 256,
        val minIntervalMs: Long = 10,
        val maxPayloadBytes: Int = 16 * 1024,
    )
}
