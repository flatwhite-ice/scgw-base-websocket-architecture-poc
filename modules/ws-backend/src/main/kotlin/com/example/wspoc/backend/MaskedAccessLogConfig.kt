package com.example.wspoc.backend

import com.example.wspoc.auth.common.TicketMasking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.http.server.logging.AccessLog

/**
 * 선택형 Reactor Netty access log(`ws.access-log.enabled=true`). URI의 WS 티켓을 가린다.
 * 기본은 꺼져 있다: 연결 단위 INFO 로그는 부하 실험 결과를 왜곡한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("ws.access-log.enabled", havingValue = "true")
class MaskedAccessLogConfig {
    @Bean
    fun maskedAccessLog() =
        NettyServerCustomizer { server ->
            server.accessLog(true) { args ->
                AccessLog.create(
                    "{} \"{} {} {}\" {} {}ms",
                    args.remoteAddress(),
                    args.method(),
                    TicketMasking.mask(args.uri() ?: ""),
                    args.protocol(),
                    args.status(),
                    args.duration(),
                )
            }
        }
}
