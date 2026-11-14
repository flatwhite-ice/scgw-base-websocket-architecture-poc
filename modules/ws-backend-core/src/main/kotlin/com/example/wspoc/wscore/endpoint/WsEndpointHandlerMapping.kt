package com.example.wspoc.wscore.endpoint

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.http.server.PathContainer
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.util.pattern.PathPatternParser

/**
 * [WsEndpoint]가 붙은 빈을 모아 만드는 [SimpleUrlHandlerMapping].
 *
 * `getBeanNamesForAnnotation`/`findAnnotationOnBean`은 클래스뿐 아니라 `@Bean` 팩토리 메서드의 어노테이션도 찾는다.
 * 잘못된 구성(WebSocketHandler가 아님, 빈 경로, 경로 중복)은 첫 요청이 아니라 기동 시점에 실패시킨다.
 */
class WsEndpointHandlerMapping private constructor(
    val endpoints: Map<String, WebSocketHandler>,
    /** `@WsEndpoint(auth = NONE)`로 선언된 경로. [com.example.wspoc.wscore.auth.WsHandshakeAuthFilter]가 인증을 건너뛴다. */
    val anonymousPaths: Set<String>,
) : SimpleUrlHandlerMapping(endpoints, ORDER) {
    private val anonymousPatterns = anonymousPaths.map(PathPatternParser.defaultInstance::parse)

    /** 이 요청 경로가 익명 엔드포인트인가. 핸들러 매핑과 같은 PathPattern 규칙으로 판단한다. */
    fun isAnonymous(path: PathContainer): Boolean = anonymousPatterns.any { it.matches(path) }

    companion object {
        /** 어노테이션 컨트롤러(RequestMappingHandlerMapping, order 0)보다 먼저 본다. */
        const val ORDER = -1

        fun from(beanFactory: ListableBeanFactory): WsEndpointHandlerMapping {
            val endpoints = linkedMapOf<String, WebSocketHandler>()
            val owners = mutableMapOf<String, String>()
            val anonymous = linkedSetOf<String>()
            for (name in beanFactory.getBeanNamesForAnnotation(WsEndpoint::class.java).sorted()) {
                val annotation = requireNotNull(beanFactory.findAnnotationOnBean(name, WsEndpoint::class.java))
                val paths = annotation.paths
                require(paths.isNotEmpty()) { "@WsEndpoint on bean '$name' declares no path" }
                val handler =
                    beanFactory.getBean(name) as? WebSocketHandler
                        ?: error("@WsEndpoint bean '$name' is not a WebSocketHandler")
                for (path in paths) {
                    owners.put(path, name)?.let { error("WebSocket path '$path' is mapped by both '$it' and '$name'") }
                    endpoints[path] = handler
                    if (annotation.auth == WsEndpoint.Auth.NONE) anonymous += path
                }
            }
            return WsEndpointHandlerMapping(endpoints, anonymous)
        }
    }
}
