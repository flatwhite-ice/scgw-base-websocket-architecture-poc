package com.example.wspoc.console

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TicketRequest(
    val token: String,
)

/** probe 요청. [credential]에 올 수 있는 값은 [ProbeCredential]에 있다. */
data class ProbeRequest(
    val path: String,
    val credential: String = "none",
    val userId: String = "console-probe",
)

data class ProbeResponse(
    val path: String,
    val credential: String,
    val results: List<ProbeResult>,
)

@RestController
@RequestMapping("/api")
class ConsoleController(
    private val props: ConsoleProperties,
    private val auth: AuthApiClient,
    private val probes: ProbeService,
    private val scraper: MetricsScraper,
) {
    @GetMapping("/config")
    fun config(): Map<String, String> =
        mapOf(
            "wsBase" to props.wsBase.toString().trimEnd('/'),
            // 화면이 보여 주는 curl 명령의 대상 주소로만 쓴다. 호출은 여전히 콘솔 서버가 중계한다.
            "authBase" to props.authBase.toString().trimEnd('/'),
        )

    /**
     * 모니터링 탭(D28): 설정된 대상(`console.monitor.targets`)의 메트릭을 지금 값으로 돌려준다.
     * 대상은 설정으로만 정한다(요청으로 주소를 받지 않는다).
     */
    @GetMapping("/metrics")
    suspend fun metrics(): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(scraper.snapshot().toJson())

    /** auth-api `/auth/token` 중계. 본문(`userId`, 선택 `ttlSeconds`)과 응답 상태를 그대로 전달한다. */
    @PostMapping("/token")
    suspend fun token(
        @RequestBody body: String,
    ): ResponseEntity<String> = auth.relayToken(body)

    /** auth-api `/auth/ws-ticket` 중계. */
    @PostMapping("/ticket")
    suspend fun ticket(
        @RequestBody request: TicketRequest,
    ): ResponseEntity<String> = auth.relayTicket(request.token)

    /** 서버에서 핸드셰이크를 대신 시도해 실제 HTTP 상태를 돌려준다. 규칙은 [ProbeService]에 있다. */
    @PostMapping("/probe")
    suspend fun probe(
        @RequestBody request: ProbeRequest,
    ): ProbeResponse {
        val results = probes.probe(request.path, request.credential, request.userId)
        // 경로와 결과만 남긴다(티켓/토큰은 남기지 않는다).
        log.info { "probe path=${request.path} credential=${request.credential} statuses=${results.map { it.status }}" }
        return ProbeResponse(request.path, request.credential, results)
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
