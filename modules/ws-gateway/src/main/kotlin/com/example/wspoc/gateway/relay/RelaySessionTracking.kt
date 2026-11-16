package com.example.wspoc.gateway.relay

import com.example.wspoc.metrics.WsMeters
import com.example.wspoc.support.drain.DrainState
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.socket.client.WebSocketClient
import org.springframework.web.reactive.socket.server.WebSocketService

/**
 * SCG가 relay 중인 세션을 게이트웨이가 볼 수 있게 한다(drain, 메트릭, 세션 로그).
 *
 * SCG는 클라이언트 세션을 외부에 노출하지 않는다. `WebsocketRoutingFilter`가 `WebSocketService` 빈을 호출하고 세션을
 * private 핸들러 안에 둔다. 그래서 SCG 필터를 복사하거나 교체하지 않고, SCG가 쓰는 빈 두 개를 감싼다.
 *  - `WebSocketService` → [TrackingWebSocketService]: 클라이언트 쪽 세션의 등록, drain, 메트릭, 로그
 *  - `WebSocketClient` → [TrackingWebSocketClient]: upstream 연결 결과와 소요 시간
 * relay 동작 자체는 건드리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class RelaySessionTracking {
    companion object {
        /** 다른 빈보다 먼저 등록되도록 static으로 둔다. 의존 빈은 감싸는 시점에 지연 조회한다. */
        @JvmStatic
        @Bean
        fun relaySessionTrackingPostProcessor(
            registry: ObjectProvider<RelaySessionRegistry>,
            drainState: ObjectProvider<DrainState>,
            meters: ObjectProvider<WsMeters>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when (bean) {
                        is WebSocketService ->
                            TrackingWebSocketService(bean, registry.getObject(), drainState.getObject(), meters.getObject())

                        is WebSocketClient -> TrackingWebSocketClient(bean, meters.getObject())

                        else -> bean
                    }
            }

        /** 클라이언트 세션의 라우트 id를 upstream 연결 측정([TrackingWebSocketClient])에 넘기는 Reactor context 키. */
        const val ROUTE_CONTEXT_KEY = "wspoc.route"
    }
}
