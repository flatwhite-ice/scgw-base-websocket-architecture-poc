package com.example.wspoc.backend

import org.springframework.boot.context.properties.ConfigurationProperties

/** echo/push 서비스 전용 설정(`ws.push`). 공통 설정은 core의 `WsProperties`(`ws.*`)에 있다. */
@ConfigurationProperties("ws.push")
data class PushProperties(
    val defaultTopic: String = "ticker",
    val defaultIntervalMs: Long = 1000,
    val defaultPayloadBytes: Int = 256,
    val minIntervalMs: Long = 10,
    /** subscribe의 `intervalMs` 상한. 상한이 없으면 다음 전송 시각 계산이 overflow한다. */
    val maxIntervalMs: Long = 3_600_000,
    val maxPayloadBytes: Int = 16 * 1024,
)
