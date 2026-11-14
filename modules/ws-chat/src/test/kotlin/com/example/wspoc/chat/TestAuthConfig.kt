package com.example.wspoc.chat

import com.example.wspoc.auth.common.KeySource
import com.example.wspoc.wscore.testing.TestKeys
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration(proxyBeanMethods = false)
class TestAuthConfig {
    @Bean
    fun testKeySource(): KeySource = TestKeys.keySource
}
