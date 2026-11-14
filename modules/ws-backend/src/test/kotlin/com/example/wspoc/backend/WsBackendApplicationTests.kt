package com.example.wspoc.backend

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(properties = ["ws.auth.mode=self"])
@Import(TestAuthConfig::class)
class WsBackendApplicationTests {
    @Test
    fun contextLoads() {
    }
}
