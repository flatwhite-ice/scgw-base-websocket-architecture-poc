package com.example.wspoc.console

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

/**
 * auth-api 호출. 화면용 중계([relayToken], [relayTicket])는 상태 코드와 본문을 그대로 돌려주고,
 * probe용 [issueTicket]은 실패하면 502로 끝낸다.
 */
@Component
class AuthApiClient(
    props: ConsoleProperties,
    private val mapper: JsonMapper,
) {
    // Boot 4에서는 WebClient.Builder 자동 구성이 별도 모듈이라 직접 만든다(의존성 하나로 충분).
    private val client = WebClient.builder().baseUrl(props.authBase.toString()).build()

    suspend fun relayToken(body: String): ResponseEntity<String> = relay(client.post().uri("/auth/token").bodyValue(body))

    suspend fun relayTicket(accessToken: String): ResponseEntity<String> =
        relay(client.post().uri("/auth/ws-ticket").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))

    /** probe용: 토큰을 받고 그 토큰으로 티켓까지 받는다. */
    suspend fun issueTicket(userId: String): String = field(relayTicket(issueToken(userId)), "ticket")

    suspend fun issueToken(userId: String): String = field(relayToken(mapper.writeValueAsString(mapOf("userId" to userId))), "token")

    private fun field(
        response: ResponseEntity<String>,
        name: String,
    ): String {
        if (!response.statusCode.is2xxSuccessful) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "auth-api -> ${response.statusCode.value()}")
        }
        return mapper.readTree(response.body.orEmpty()).path(name).asString()
    }

    private suspend fun relay(request: WebClient.RequestHeadersSpec<*>): ResponseEntity<String> =
        request
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .exchangeToMono { response ->
                response
                    .bodyToMono<String>()
                    .defaultIfEmpty("")
                    .map { ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON).body(it) }
            }.onErrorResume { error ->
                // auth-api가 떠 있지 않은 경우 등: 화면에 원인을 보여 준다.
                Mono.just(
                    ResponseEntity
                        .status(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(mapOf("error" to "auth-api unreachable: ${error.javaClass.simpleName}"))),
                )
            }.awaitSingle()
}
