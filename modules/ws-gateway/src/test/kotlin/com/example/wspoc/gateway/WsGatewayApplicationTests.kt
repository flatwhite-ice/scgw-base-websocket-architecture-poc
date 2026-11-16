package com.example.wspoc.gateway

import com.example.wspoc.wscore.testing.TestKeySourceConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(properties = ["ws.gateway.auth.jwks-uri="])
@Import(TestKeySourceConfig::class)
class WsGatewayApplicationTests {
    @Test
    fun contextLoads() {
    }
}
