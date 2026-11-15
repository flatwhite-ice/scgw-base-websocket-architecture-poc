package com.example.wspoc.wscore

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

/**
 * WebSocket 서비스 공통 설정(`ws.*`). 서비스별 설정은 각 서비스가 따로 둔다(예: ws-backend의 `ws.push`).
 */
@ConfigurationProperties("ws")
data class WsProperties(
    val auth: Auth = Auth(),
    val heartbeat: Heartbeat = Heartbeat(),
    val drain: Drain = Drain(),
    val maxFramePayload: DataSize = DataSize.ofKilobytes(64),
    /** 송신 메시지의 `server` 필드 값을 덮어쓴다(기본 `<app>@<host>:<port>`). */
    val instanceId: String? = null,
    /** 세션별 송신 큐 크기. 가득 차면 push 메시지를 무한히 쌓지 않고 버리며, 버린 수를 센다. */
    val outboundBufferSize: Int = 256,
    val metrics: Metrics = Metrics(),
) {
    data class Metrics(
        /**
         * Reactor Netty 내장 메트릭(연결 수, 송수신 바이트, ByteBuf 할당자, event loop 대기 작업)을 켠다.
         * 연결마다 계측 핸들러가 하나 붙으므로, 그 비용까지 빼고 재려는 실험에서는 끈다.
         */
        val netty: Boolean = true,
        /** `ws.eventloop.lag` 측정 주기. 0이면 측정하지 않는다. */
        val eventLoopLagInterval: Duration = Duration.ofMillis(100),
    )

    enum class AuthMode {
        /** Relay: ws-gateway가 넣어 준 X-User-Id / X-Scopes / X-Auth-Exp를 신뢰한다. */
        GATEWAY,

        /** Direct: 핸드셰이크에서 `?ticket=` 또는 `Authorization: Bearer`를 직접 검증한다. */
        SELF,
    }

    data class Auth(
        val mode: AuthMode = AuthMode.GATEWAY,
        /** auth-api의 JWKS. SELF 모드에서 필요하다(`direct` 프로필이 설정). 다른 KeySource 빈이 있으면 없어도 된다. */
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
}
