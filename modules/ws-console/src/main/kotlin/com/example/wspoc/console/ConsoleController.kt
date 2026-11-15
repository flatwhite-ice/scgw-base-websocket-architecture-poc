package com.example.wspoc.console

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI

data class TicketRequest(
    val token: String,
)

/**
 * probe 요청. [credential]:
 *  - `none`: 자격증명 없음
 *  - `ticket`: `?ticket=` (브라우저와 같은 방식)
 *  - `bearer`: `Authorization: Bearer` (브라우저는 못 붙이는 헤더)
 *  - `spoof`: 자격증명 없이 위조한 `X-User-Id`/`X-Scopes`만
 *  - `replay`: 티켓 하나로 두 번 연결(두 번째는 재사용이므로 401이어야 한다)
 */
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
    private val prober: HandshakeProber,
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

    @PostMapping("/probe")
    suspend fun probe(
        @RequestBody request: ProbeRequest,
    ): ProbeResponse {
        // 경로만 받고 호스트와 query는 서버가 정한다: 임의 주소로 접속하는 데 쓰이지 않게 한다.
        if (!SAFE_PATH.matches(request.path) || request.path.contains("..")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "path must look like /ws/...")
        }
        val base = props.wsBase.toString().trimEnd('/') + request.path
        val results =
            when (request.credential) {
                "none" -> {
                    listOf(prober.probe("no credentials", URI.create(base)))
                }

                "ticket" -> {
                    listOf(prober.probe("ticket", URI.create("$base?ticket=${auth.issueTicket(request.userId)}")))
                }

                "bearer" -> {
                    val headers = HttpHeaders().apply { setBearerAuth(auth.issueToken(request.userId)) }
                    listOf(prober.probe("bearer", URI.create(base), headers))
                }

                "spoof" -> {
                    val headers = HttpHeaders().apply { SPOOFED_HEADERS.forEach { (k, v) -> add(k, v) } }
                    listOf(prober.probe("spoofed identity headers", URI.create(base), headers))
                }

                "replay" -> {
                    val uri = URI.create("$base?ticket=${auth.issueTicket(request.userId)}")
                    listOf(prober.probe("first use", uri), prober.probe("replay", uri))
                }

                else -> {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown credential: ${request.credential}")
                }
            }
        // 경로와 결과만 남긴다(티켓/토큰은 남기지 않는다).
        log.info { "probe path=${request.path} credential=${request.credential} statuses=${results.map { it.status }}" }
        return ProbeResponse(request.path, request.credential, results)
    }

    private companion object {
        val log = KotlinLogging.logger {}
        val SAFE_PATH = Regex("^/ws(/[A-Za-z0-9._~-]+)+$")
        val SPOOFED_HEADERS = mapOf("X-User-Id" to "spoofed-admin", "X-Scopes" to "admin", "X-Auth-Exp" to "9999999999")
    }
}
