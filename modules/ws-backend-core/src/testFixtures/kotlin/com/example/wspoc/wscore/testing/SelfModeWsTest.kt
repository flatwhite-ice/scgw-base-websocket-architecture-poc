package com.example.wspoc.wscore.testing

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * self 모드(Direct, 서비스가 JWT를 직접 검증) WebSocket 서비스 테스트의 공통 구성.
 * 랜덤 포트로 서버를 띄우고, JWKS 조회를 끄고, [TestKeys]로 발급한 토큰과 티켓을 신뢰한다.
 *
 * 테스트마다 다른 설정은 `@TestPropertySource(properties = [...])`로, 추가 설정 클래스는 `@Import`로 더한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri="],
)
@Import(TestKeySourceConfig::class)
annotation class SelfModeWsTest
