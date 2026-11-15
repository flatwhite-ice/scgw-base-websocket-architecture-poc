package com.example.wspoc.wscore.session

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import java.net.InetSocketAddress

/**
 * 세션 열림/종료 로그(D21). 전용 로거 `ws.session`에 INFO로 남기며 기본은 켜져 있다.
 * 부하 실험에서는 `logging.level.ws.session=WARN`으로 끈다: 2만 세션 규모에서 연결 단위 로그는 결과를 왜곡한다.
 *
 * 형식은 grep하기 쉬운 `key=value`다. URI는 경로만 남긴다(쿼리의 `ticket`이 로그에 남지 않게).
 * 메시지는 람다로 넘겨 로거가 꺼져 있으면 문자열을 만들지 않는다.
 */
object SessionLog {
    const val LOGGER_NAME = "ws.session"

    private val log = KotlinLogging.logger(LOGGER_NAME)

    fun opened(
        session: WebSocketSession,
        userId: String,
        anonymous: Boolean,
        active: Int,
    ) = log.info {
        "ws session opened id=${session.id} path=${session.handshakeInfo.uri.path} user=$userId anonymous=$anonymous " +
            "remote=${session.handshakeInfo.remoteAddress.format()} active=$active"
    }

    fun closed(
        session: WebSocketSession,
        userId: String,
        status: CloseStatus,
        closedBy: String,
        durationMs: Long,
        active: Int,
    ) = log.info {
        "ws session closed id=${session.id} path=${session.handshakeInfo.uri.path} user=$userId code=${status.code} " +
            "reason=${status.reason.orEmpty().ifEmpty { "-" }} closedBy=$closedBy durationMs=$durationMs active=$active"
    }

    private fun InetSocketAddress?.format() = this?.let { "${it.hostString}:${it.port}" } ?: "-"
}
