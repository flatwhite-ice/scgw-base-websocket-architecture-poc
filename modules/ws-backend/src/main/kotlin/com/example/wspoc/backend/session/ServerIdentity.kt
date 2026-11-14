package com.example.wspoc.backend.session

import com.example.wspoc.backend.WsProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Identifies this backend instance in every message it sends (`"server"` field), so a client connected through
 * the gateway can tell which backend served it. Format: `<app>@<host>:<port>`, or `ws.instance-id` if set.
 *
 * Resolved once at startup (main thread): `HOSTNAME` (the pod name in K8s) or a local hostname lookup, which can
 * block on DNS and therefore must never run on an event loop. The port is the one actually bound.
 */
@Component
class ServerIdentity(
    props: WsProperties,
    @Value("\${spring.application.name:ws-backend}") private val appName: String,
) : ApplicationListener<WebServerInitializedEvent> {
    private val configuredId = props.instanceId?.takeIf { it.isNotBlank() }
    private val host = System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: InetAddress.getLocalHost().hostName

    private val current = AtomicReference(configuredId ?: "$appName@$host")

    val id: String get() = current.get()

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        if (configuredId == null) current.set("$appName@$host:${event.webServer.port}")
    }
}
