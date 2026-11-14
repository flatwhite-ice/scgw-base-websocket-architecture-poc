package com.example.wspoc.wscore.session

import com.example.wspoc.wscore.WsProperties
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * 이 backend 인스턴스가 보내는 모든 메시지에 자신을 표시한다(`"server"` 필드). 게이트웨이를 거쳐 연결한
 * 클라이언트도 어느 backend가 처리했는지 알 수 있다. 형식: `<app>@<host>:<port>`, 또는 설정된 `ws.instance-id`.
 *
 * 기동 시 한 번(main 스레드) 결정한다: `HOSTNAME`(K8s에서는 파드 이름) 또는 로컬 호스트명 조회.
 * 후자는 DNS 때문에 블로킹될 수 있어 event loop에서 실행하면 안 된다. 포트는 실제로 바인딩된 값이다.
 */
class ServerIdentity(
    props: WsProperties,
    private val appName: String,
) : ApplicationListener<WebServerInitializedEvent> {
    private val configuredId = props.instanceId?.takeIf { it.isNotBlank() }
    private val host = System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: InetAddress.getLocalHost().hostName

    private val current = AtomicReference(configuredId ?: "$appName@$host")

    val id: String get() = current.get()

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        if (configuredId == null) current.set("$appName@$host:${event.webServer.port}")
    }
}
