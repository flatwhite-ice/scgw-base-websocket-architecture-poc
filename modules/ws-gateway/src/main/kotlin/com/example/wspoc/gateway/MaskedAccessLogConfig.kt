package com.example.wspoc.gateway

import com.example.wspoc.auth.common.TicketMasking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.http.server.logging.AccessLog

/**
 * Opt-in Reactor Netty access log (`ws.access-log.enabled=true`) with WS tickets masked in the URI.
 * Off by default: per-connection INFO logging would distort the load experiments.
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
