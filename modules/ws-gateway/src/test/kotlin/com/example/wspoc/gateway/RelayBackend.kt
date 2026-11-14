package com.example.wspoc.gateway

import com.example.wspoc.backend.WsBackendApplication
import com.example.wspoc.chat.WsChatApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.web.server.WebFilter
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/** `/ws/` 핸드셰이크에서 backend가 실제로 받은 내용(게이트웨이가 다시 쓴 뒤). */
data class SeenHandshake(
    val uri: URI,
    val headers: HttpHeaders,
)

/**
 * 테스트 대상 게이트웨이 뒤에 서는 실제 WebSocket 서비스(gateway 인증 모드). 처음 쓸 때 JVM당 한 번 랜덤 포트로 띄운다.
 * 받은 `/ws/` 요청을 모두 기록해서, relay 홉을 무엇이 넘어왔는지와 어느 서비스로 라우팅됐는지 테스트가 확인할 수 있게 한다.
 */
open class RelayApp(
    private val appClass: Class<*>,
    private val appName: String,
) {
    val seen = CopyOnWriteArrayList<SeenHandshake>()

    private val context: ConfigurableApplicationContext by lazy {
        SpringApplicationBuilder(appClass)
            .initializers({ ctx ->
                // 핸드셰이크 기록 필터. 빈으로 직접 등록해서 게이트웨이 컨텍스트의 component scan과 섞이지 않게 한다.
                (ctx as GenericApplicationContext).registerBean("handshakeRecorder", WebFilter::class.java, Supplier { recorder() })
            })
            .properties(
                // 공유 테스트 클래스패스에 있는 게이트웨이(와 다른 서비스)의 application.yml을 읽지 않게 한다.
                "spring.config.name=ws-backend-e2e",
                "spring.application.name=$appName",
                "server.port=0",
                "ws.auth.mode=gateway",
                "ws.heartbeat.interval=30s",
                // 게이트웨이 starter도 같은 클래스패스에 있다. backend가 게이트웨이처럼 동작하면 안 된다.
                "spring.cloud.gateway.server.webflux.enabled=false",
                "spring.cloud.discovery.enabled=false",
            ).run()
    }

    val port: Int get() = requireNotNull((context as WebServerApplicationContext).webServer).port

    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    private fun recorder() =
        WebFilter { exchange, chain ->
            if (exchange.request.path
                    .value()
                    .startsWith("/ws/")
            ) {
                seen += SeenHandshake(exchange.request.uri, HttpHeaders.readOnlyHttpHeaders(exchange.request.headers))
            }
            chain.filter(exchange)
        }
}

/** echo/push 서비스(`/ws/echo`, `/ws/push`). */
object RelayBackend : RelayApp(WsBackendApplication::class.java, "ws-backend")

/** 채팅 서비스(`/ws/chat`). */
object RelayChat : RelayApp(WsChatApplication::class.java, "ws-chat")
