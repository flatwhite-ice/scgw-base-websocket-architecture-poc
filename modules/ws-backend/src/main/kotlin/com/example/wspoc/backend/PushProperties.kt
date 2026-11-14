package com.example.wspoc.backend

import org.springframework.boot.context.properties.ConfigurationProperties

/** echo/push 서비스 전용 설정(`ws.push`). 공통 설정은 core의 `WsProperties`(`ws.*`)에 있다. */
@ConfigurationProperties("ws.push")
data class PushProperties(
    val defaultTopic: String = "ticker",
    val defaultIntervalMs: Long = 1000,
    val defaultPayloadBytes: Int = 256,
    val minIntervalMs: Long = 10,
    val maxPayloadBytes: Int = 16 * 1024,
)
