package com.example.wspoc.gateway.relay

import com.example.wspoc.support.session.SessionLogFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Duration

/**
 * 게이트웨이 쪽 relay 세션 열림/종료 로그(D21). backend와 같은 로거 이름 `ws.session`을 쓴다. INFO로 남기며 기본은 켜져 있고,
 * 부하 실험에서는 `logging.level.ws.session=WARN`으로 끈다. `active`는 이 게이트웨이가 relay 중인 클라이언트 세션 수다.
 */
object RelaySessionLog {
    private val log = KotlinLogging.logger(SessionLogFormat.LOGGER_NAME)

    fun opened(
        session: WebSocketSession,
        ctx: RelayContext,
        active: Int,
    ) = log.info {
        "ws relay opened id=${session.id} route=${ctx.routeId} path=${ctx.path} user=${ctx.userId} " +
            "upstream=${ctx.upstream} remote=${ctx.remote} active=$active"
    }

    /** close 코드는 클라이언트 세션 기준이다. relay는 양방향 close를 그대로 전달하므로 backend 코드와 같다(D14). */
    fun closed(
        session: WebSocketSession,
        ctx: RelayContext,
        status: CloseStatus,
        duration: Duration,
        active: Int,
    ) = log.info {
        "ws relay closed id=${session.id} route=${ctx.routeId} path=${ctx.path} user=${ctx.userId} " +
            "upstream=${ctx.upstream} code=${status.code} reason=${SessionLogFormat.reason(status)} " +
            "durationMs=${duration.toMillis()} active=$active"
    }
}
