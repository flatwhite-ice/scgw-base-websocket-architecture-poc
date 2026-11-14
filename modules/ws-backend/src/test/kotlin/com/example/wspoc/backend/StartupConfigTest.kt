package com.example.wspoc.backend

import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.KeySource
import org.junit.jupiter.api.Nested
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Regression: the app must start with the shipped config, with and without profiles (no test overrides). */
class StartupConfigTest {
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    inner class NoProfile(
        @Autowired val props: WsProperties,
        @Autowired val keySource: ObjectProvider<KeySource>,
        @Autowired val verifier: ObjectProvider<JwtVerifier>,
    ) {
        @Test
        fun `starts in self mode with JWKS from local auth-api`() {
            assertEquals(WsProperties.AuthMode.SELF, props.auth.mode)
            assertNotNull(keySource.ifAvailable as? JwksKeySourceLifecycle)
            assertNotNull(verifier.ifAvailable)
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("direct")
    inner class DirectProfile(
        @Autowired val props: WsProperties,
        @Autowired val keySource: ObjectProvider<KeySource>,
    ) {
        @Test
        fun `starts in self mode with JWKS`() {
            assertEquals(WsProperties.AuthMode.SELF, props.auth.mode)
            assertNotNull(keySource.ifAvailable as? JwksKeySourceLifecycle)
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("relay")
    inner class RelayProfile(
        @Autowired val props: WsProperties,
        @Autowired val keySource: ObjectProvider<KeySource>,
        @Autowired val verifier: ObjectProvider<JwtVerifier>,
    ) {
        @Test
        fun `starts in gateway mode without a JWKS refresh loop`() {
            assertEquals(WsProperties.AuthMode.GATEWAY, props.auth.mode)
            assertNull(keySource.ifAvailable)
            assertNull(verifier.ifAvailable)
        }
    }
}
