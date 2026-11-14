package com.example.wspoc.wscore.endpoint

/**
 * 이 [org.springframework.web.reactive.socket.WebSocketHandler] 빈이 맡을 핸드셰이크 경로.
 * [WsEndpointHandlerMapping]이 기동 시점에 모아 URL 매핑으로 등록한다.
 *
 * 두 위치에 붙일 수 있다.
 *  - `WebSocketHandler`를 구현한 `@Component` 클래스(서비스 패키지에서 스캔되는 경우)
 *  - `WebSocketHandler`를 반환하는 `@Bean` 메서드(같은 클래스를 설정만 달리해 여러 경로에 쓸 때)
 *
 * 매핑은 연결당 한 번(업그레이드 시점)만 일어난다. 연결 안의 메시지 분기는 핸들러가 직접 한다.
 *
 * [auth]가 [Auth.NONE]이면 핸드셰이크 인증을 건너뛰는 익명 엔드포인트다(D20). 세션은 익명 principal로 열리고,
 * 토큰 만료 종료(3000)가 없다. heartbeat와 drain은 똑같이 적용된다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class WsEndpoint(
    vararg val paths: String,
    val auth: Auth = Auth.REQUIRED,
) {
    enum class Auth {
        /** relay: 게이트웨이 신원 헤더, direct: `?ticket=` 또는 Bearer가 있어야 한다. 없으면 업그레이드 전에 401. */
        REQUIRED,

        /** 인증 없이 연결한다. 보내 온 자격증명이나 신원 헤더는 무시한다. */
        NONE,
    }
}
