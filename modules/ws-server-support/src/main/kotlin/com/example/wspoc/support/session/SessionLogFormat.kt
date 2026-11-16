package com.example.wspoc.support.session

import org.springframework.web.reactive.socket.CloseStatus
import java.net.InetSocketAddress

/**
 * 세션 열림/종료 로그(D21)의 공통 표기. 서비스와 게이트웨이가 같은 로거 이름과 같은 `key=value` 표기를 써서
 * 한 번의 grep으로 양쪽을 함께 볼 수 있게 한다.
 */
object SessionLogFormat {
    /** 전용 로거. INFO로 남기며, 부하 실험에서는 `logging.level.ws.session=WARN`으로 끈다. */
    const val LOGGER_NAME = "ws.session"

    /** 값이 없을 때의 표기. */
    const val NONE = "-"

    /** `host:port`. 주소를 모르면 `-`. */
    fun hostPort(address: InetSocketAddress?): String = address?.let { "${it.hostString}:${it.port}" } ?: NONE

    /** close 프레임의 reason. 비어 있으면 `-`. */
    fun reason(status: CloseStatus): String = status.reason.orEmpty().ifEmpty { NONE }
}
