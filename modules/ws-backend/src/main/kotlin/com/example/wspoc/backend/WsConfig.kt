package com.example.wspoc.backend

import com.example.wspoc.wscore.endpoint.WsEndpoint
import com.example.wspoc.wscore.session.ManagedWsHandler
import com.example.wspoc.wscore.session.WsSessionRuntime
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/** echo/push 서비스의 엔드포인트. 인증, 세션 수명, drain은 ws-backend-core가 맡는다(D17). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties::class)
class WsConfig {
    @Bean
    @WsEndpoint("/ws/echo")
    fun echoHandler(
        runtime: WsSessionRuntime,
        push: PushProperties,
        jsonMapper: JsonMapper,
    ) = ManagedWsHandler(runtime) { EchoPushProtocol(push, jsonMapper, autoSubscribe = false) }

    @Bean
    @WsEndpoint("/ws/push")
    fun pushHandler(
        runtime: WsSessionRuntime,
        push: PushProperties,
        jsonMapper: JsonMapper,
    ) = ManagedWsHandler(runtime) { EchoPushProtocol(push, jsonMapper, autoSubscribe = true) }

    /** 로그인 없이 받는 공개 push(읽기 전용). 게이트웨이에서도 WsAuth 필터가 없는 라우트로 연결된다(D20). */
    @Bean
    @WsEndpoint("/ws/public/push", auth = WsEndpoint.Auth.NONE)
    fun publicPushHandler(
        runtime: WsSessionRuntime,
        push: PushProperties,
        jsonMapper: JsonMapper,
    ) = ManagedWsHandler(runtime) { EchoPushProtocol(push, jsonMapper, autoSubscribe = true, readOnly = true) }
}
