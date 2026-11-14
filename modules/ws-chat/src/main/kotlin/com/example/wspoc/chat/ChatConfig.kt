package com.example.wspoc.chat

import com.example.wspoc.wscore.endpoint.WsEndpoint
import com.example.wspoc.wscore.session.ManagedWsHandler
import com.example.wspoc.wscore.session.WsSessionRuntime
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/** 채팅 서비스의 엔드포인트. 인증, 세션 수명, drain은 ws-backend-core가 맡는다(D17). */
@Configuration(proxyBeanMethods = false)
class ChatConfig {
    @Bean
    fun chatRooms() = ChatRooms()

    @Bean
    @WsEndpoint("/ws/chat")
    fun chatHandler(
        runtime: WsSessionRuntime,
        rooms: ChatRooms,
        jsonMapper: JsonMapper,
    ) = ManagedWsHandler(runtime) { ChatProtocol(rooms, jsonMapper) }
}
