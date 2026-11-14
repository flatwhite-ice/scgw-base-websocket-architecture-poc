package com.example.wspoc.console

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("console")
data class ConsoleProperties(
    /** 토큰/티켓 발급을 중계할 auth-api 주소. */
    val authBase: URI = URI.create("http://localhost:8070"),
    /** 화면과 probe가 연결할 WebSocket 주소(게이트웨이). */
    val wsBase: URI = URI.create("ws://localhost:8080"),
    /** probe 핸드셰이크 제한 시간. */
    val probeTimeout: Duration = Duration.ofSeconds(3),
)
