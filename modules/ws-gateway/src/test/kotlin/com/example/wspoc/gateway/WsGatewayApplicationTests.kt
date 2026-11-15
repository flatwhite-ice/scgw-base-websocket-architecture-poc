package com.example.wspoc.gateway

import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.wscore.testing.TestKeys
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@SpringBootTest(properties = ["ws.gateway.auth.jwks-uri="])
@Import(WsGatewayApplicationTests.Keys::class)
class WsGatewayApplicationTests {
    @TestConfiguration(proxyBeanMethods = false)
    class Keys {
        @Bean
        fun testKeySource(): KeySource = TestKeys.keySource
    }

    @Test
    fun contextLoads() {
    }
}
