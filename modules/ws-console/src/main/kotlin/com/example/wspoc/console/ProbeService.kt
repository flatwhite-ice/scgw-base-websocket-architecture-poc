package com.example.wspoc.console

import com.example.wspoc.console.ProbeCredential.BEARER
import com.example.wspoc.console.ProbeCredential.NONE
import com.example.wspoc.console.ProbeCredential.REPLAY
import com.example.wspoc.console.ProbeCredential.SPOOF
import com.example.wspoc.console.ProbeCredential.TICKET
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.net.URI

/** probe 요청이 받아들일 수 없는 값이다. 응답은 400이다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidProbeRequestException(
    message: String,
) : RuntimeException(message)

/** probe가 핸드셰이크에 붙이는 자격증명의 종류. 요청 JSON의 `credential` 값은 [wireName]이다. */
enum class ProbeCredential(
    val wireName: String,
) {
    /** 자격증명 없음 */
    NONE("none"),

    /** `?ticket=`(브라우저와 같은 방식) */
    TICKET("ticket"),

    /** `Authorization: Bearer`(브라우저는 못 붙이는 헤더) */
    BEARER("bearer"),

    /** 자격증명 없이 위조한 `X-User-Id`/`X-Scopes`만 */
    SPOOF("spoof"),

    /** 티켓 하나로 두 번 연결(두 번째는 재사용이므로 401이어야 한다) */
    REPLAY("replay"),
    ;

    companion object {
        fun of(wireName: String): ProbeCredential? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * 게이트웨이에 핸드셰이크를 대신 시도해 실제 HTTP 상태를 알아낸다(`/api/probe`, D23).
 * 대상 주소는 설정(`console.ws-base`)으로 고정하고 요청에서는 경로만 받는다: 임의 주소로 접속하는 데 쓰이지 않게 한다.
 */
@Service
class ProbeService(
    private val props: ConsoleProperties,
    private val auth: AuthApiClient,
    private val prober: HandshakeProber,
) {
    suspend fun probe(
        path: String,
        credential: String,
        userId: String,
    ): List<ProbeResult> {
        if (!SAFE_PATH.matches(path) || path.contains("..")) throw InvalidProbeRequestException("path must look like /ws/...")
        val kind = ProbeCredential.of(credential) ?: throw InvalidProbeRequestException("unknown credential: $credential")
        val base = props.wsBase.toString().trimEnd('/') + path
        return when (kind) {
            NONE -> {
                listOf(prober.probe("no credentials", URI.create(base)))
            }

            TICKET -> {
                listOf(prober.probe("ticket", withTicket(base, userId)))
            }

            BEARER -> {
                val headers = HttpHeaders().apply { setBearerAuth(auth.issueToken(userId)) }
                listOf(prober.probe("bearer", URI.create(base), headers))
            }

            SPOOF -> {
                val headers = HttpHeaders().apply { SPOOFED_HEADERS.forEach { (name, value) -> add(name, value) } }
                listOf(prober.probe("spoofed identity headers", URI.create(base), headers))
            }

            REPLAY -> {
                val uri = withTicket(base, userId)
                listOf(prober.probe("first use", uri), prober.probe("replay", uri))
            }
        }
    }

    private suspend fun withTicket(
        base: String,
        userId: String,
    ): URI = URI.create("$base?ticket=${auth.issueTicket(userId)}")

    private companion object {
        val SAFE_PATH = Regex("^/ws(/[A-Za-z0-9._~-]+)+$")
        val SPOOFED_HEADERS = mapOf("X-User-Id" to "spoofed-admin", "X-Scopes" to "admin", "X-Auth-Exp" to "9999999999")
    }
}
