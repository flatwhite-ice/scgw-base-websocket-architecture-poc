package com.example.wspoc.wscore.testing

import com.example.wspoc.auth.token.KeySource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * JWKS 조회(auth-api) 대신 [TestKeys]의 키를 신뢰하게 한다. 테스트가 `@Import`로 넣는다.
 * JWKS URI를 비워(`ws.auth.jwks-uri=`, 게이트웨이는 `ws.gateway.auth.jwks-uri=`) 실제 조회를 끈 컨텍스트에서 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKeySourceConfig {
    @Bean
    fun testKeySource(): KeySource = TestKeys.keySource
}
