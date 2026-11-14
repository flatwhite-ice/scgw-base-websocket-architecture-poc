package com.example.wspoc.console

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.net.URI
import java.util.concurrent.TimeoutException

/** 핸드셰이크 한 번의 결과. [status]는 HTTP 상태(101 = 업그레이드 성공), 연결 자체가 안 되면 null. */
data class ProbeResult(
    val label: String,
    val status: Int?,
    val detail: String,
)

/**
 * 서버에서 대신 핸드셰이크를 시도해 실제 HTTP 상태를 알아낸다. 브라우저 WebSocket API는 업그레이드가
 * 거절돼도 상태 코드 없이 close 1006만 알려 주기 때문이다(D23). 성공하면 바로 1000으로 닫는다.
 */
@Component
class HandshakeProber(
    private val props: ConsoleProperties,
) {
    private val client = ReactorNettyWebSocketClient()

    suspend fun probe(
        label: String,
        uri: URI,
        headers: HttpHeaders = HttpHeaders(),
    ): ProbeResult =
        try {
            client
                .execute(uri, headers) { session -> session.close(CloseStatus.NORMAL) }
                .timeout(props.probeTimeout)
                .awaitSingleOrNull()
            ProbeResult(label, 101, "upgraded")
        } catch (e: Exception) {
            failure(label, e)
        }

    private fun failure(
        label: String,
        error: Throwable,
    ): ProbeResult {
        val handshake = generateSequence(error) { it.cause }.filterIsInstance<WebSocketClientHandshakeException>().firstOrNull()
        val status = handshake?.response()?.status()
        return when {
            status != null -> ProbeResult(label, status.code(), status.reasonPhrase())
            error is TimeoutException -> ProbeResult(label, null, "timeout")
            else -> ProbeResult(label, null, "${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
