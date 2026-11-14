package com.example.wspoc.backend.endpoint

/**
 * 이 [org.springframework.web.reactive.socket.WebSocketHandler] 빈이 맡을 핸드셰이크 경로.
 * [WsEndpointHandlerMapping]이 기동 시점에 모아 URL 매핑으로 등록한다.
 *
 * 두 위치에 붙일 수 있다.
 *  - `WebSocketHandler`를 구현한 `@Component` 클래스
 *  - `WebSocketHandler`를 반환하는 `@Bean` 메서드(같은 클래스를 설정만 달리해 여러 경로에 쓸 때)
 *
 * 매핑은 연결당 한 번(업그레이드 시점)만 일어난다. 연결 안의 메시지 분기는 핸들러가 직접 한다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class WsEndpoint(
    vararg val paths: String,
)
