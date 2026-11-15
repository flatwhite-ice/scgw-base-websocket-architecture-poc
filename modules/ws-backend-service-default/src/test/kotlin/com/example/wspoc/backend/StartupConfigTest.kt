package com.example.wspoc.backend

import com.example.wspoc.auth.token.JwtVerifier
import com.example.wspoc.auth.token.KeySource
import com.example.wspoc.wscore.JwksKeySourceLifecycle
import com.example.wspoc.wscore.WsProperties
import org.junit.jupiter.api.Nested
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 회귀 방지: 배포 설정 그대로(테스트 덮어쓰기 없이) 프로필 유무와 관계없이 앱이 기동해야 한다. */
class StartupConfigTest {
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    inner class NoProfile(
        @Autowired val props: WsProperties,
        @Autowired val keySource: ObjectProvider<KeySource>,
        @Autowired val verifier: ObjectProvider<JwtVerifier>,
    ) {
        @Test
        fun `defaults to relay (gateway mode) without a JWKS refresh loop`() {
            assertEquals(WsProperties.AuthMode.GATEWAY, props.auth.mode)
            assertNull(keySource.ifAvailable)
            assertNull(verifier.ifAvailable)
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
