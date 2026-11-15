package com.example.wspoc.gateway.drain

import com.example.wspoc.auth.token.GatewayHeaders
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.time.Duration

/** 세션 로그에 남길 relay 정보. 업그레이드 시점의 exchange에서 한 번 뽑아 둔다. */
data class RelayLogContext(
    val path: String,
    val routeId: String,
    /** `WsAuth`가 넣은 신원. 익명 라우트면 `-`. */
    val userId: String,
    /** LoadBalancer가 고른 backend(`host:port`). */
    val upstream: String,
    val remote: String,
) {
    companion object {
        fun of(exchange: ServerWebExchange): RelayLogContext {
            val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
            val upstream = exchange.getAttribute<URI>(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)
            return RelayLogContext(
                // 쿼리는 남기지 않는다(`ticket`). WsAuth 라우트는 이미 지웠지만 익명 라우트는 그대로일 수 있다.
                path = exchange.request.path.value(),
                routeId = route?.id ?: "-",
                userId = exchange.request.headers.getFirst(GatewayHeaders.USER_ID) ?: "-",
                upstream = upstream?.let { "${it.host}:${it.port}" } ?: "-",
                remote = exchange.request.remoteAddress?.let { "${it.hostString}:${it.port}" } ?: "-",
            )
        }
    }
}

/**
 * 게이트웨이 쪽 relay 세션 열림/종료 로그(D21). backend와 같은 로거 이름 `ws.session`을 쓴다. INFO로 남기며 기본은 켜져 있고,
 * 부하 실험에서는 `logging.level.ws.session=WARN`으로 끈다. `active`는 이 게이트웨이가 relay 중인 클라이언트 세션 수다.
 */
object RelaySessionLog {
    private val log = KotlinLogging.logger("ws.session")

    fun opened(
        session: WebSocketSession,
        ctx: RelayLogContext,
        active: Int,
    ) = log.info {
        "ws relay opened id=${session.id} route=${ctx.routeId} path=${ctx.path} user=${ctx.userId} " +
            "upstream=${ctx.upstream} remote=${ctx.remote} active=$active"
    }

    /** close 코드는 클라이언트 세션 기준이다. relay는 양방향 close를 그대로 전달하므로 backend 코드와 같다(D14). */
    fun closed(
        session: WebSocketSession,
        ctx: RelayLogContext,
        status: CloseStatus,
        duration: Duration,
        active: Int,
    ) = log.info {
        "ws relay closed id=${session.id} route=${ctx.routeId} path=${ctx.path} user=${ctx.userId} " +
            "upstream=${ctx.upstream} code=${status.code} reason=${status.reason.orEmpty().ifEmpty { "-" }} " +
            "durationMs=${duration.toMillis()} active=$active"
    }
}
