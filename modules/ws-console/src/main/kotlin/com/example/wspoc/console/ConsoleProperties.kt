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
    val monitor: Monitor = Monitor(),
) {
    /** 모니터링 탭(D28): 콘솔 서버가 대신 긁어 올 메트릭 대상. */
    data class Monitor(
        /** 대상 프로세스. 화면의 카드와 차트 색은 이 순서를 따른다. */
        val targets: List<Target> =
            listOf(
                Target("ws-gateway", URI.create("http://localhost:8080")),
                Target("ws-backend", URI.create("http://localhost:8090")),
                Target("ws-chat", URI.create("http://localhost:8091")),
            ),
        /** 대상마다 붙이는 Prometheus 노출 경로. */
        val path: String = "/actuator/prometheus",
        /** 화면이 `/api/metrics`를 부르는 주기. 화면이 여러 개 열려 있어도 대상은 이 값의 절반보다 자주 긁지 않는다. */
        val refresh: Duration = Duration.ofSeconds(2),
        /** 대상 하나를 긁는 제한 시간. 넘기면 그 대상만 down으로 표시한다. */
        val scrapeTimeout: Duration = Duration.ofSeconds(2),
    )

    data class Target(
        val name: String,
        val url: URI,
    )
}
