package com.example.wspoc.auth.common

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwksKeySourceTest {
    private val served = AtomicReference<List<RSAKey>>(emptyList())
    private val hits = AtomicInteger()

    /** 200이 아니면 그 상태로 응답한다(auth-api가 아직 준비되지 않은 상황 흉내). */
    private val status = AtomicInteger(200)
    private val server: DisposableServer =
        HttpServer
            .create()
            .port(0)
            .route { routes ->
                routes.get("/.well-known/jwks.json") { _, res ->
                    hits.incrementAndGet()
                    if (status.get() != 200) return@get res.status(status.get()).send()
                    val body = JWKSet(served.get().map { it.toPublicJWK() }).toString()
                    res.header("content-type", "application/json").sendString(Mono.just(body))
                }
            }.bindNow()
    private val uri = URI.create("http://localhost:${server.port()}/.well-known/jwks.json")

    @AfterTest
    fun tearDown() {
        server.disposeNow()
    }

    @Test
    fun `verifies tokens with keys fetched from JWKS and caches them`() =
        runBlocking {
            val key = RsaKeys.generate("k1")
            served.set(listOf(key))
            val source = JwksKeySource(uri, minRefetchInterval = Duration.ZERO)
            val token = JwtIssuer(key).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            repeat(3) { assertIs<AuthResult.Success>(JwtVerifier(source).verify(token.token, TokenType.ACCESS)) }
            assertEquals(1, hits.get())
        }

    @Test
    fun `unknown kid triggers refetch so rotated keys are picked up`() =
        runBlocking {
            val old = RsaKeys.generate("old")
            val new = RsaKeys.generate("new")
            served.set(listOf(old))
            val source = JwksKeySource(uri, minRefetchInterval = Duration.ZERO)
            source.refresh()

            served.set(listOf(old, new))
            val token = JwtIssuer(new).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            assertIs<AuthResult.Success>(JwtVerifier(source).verify(token.token, TokenType.ACCESS))
            assertEquals(2, hits.get())
        }

    @Test
    fun `refetch on unknown kid is rate limited`() =
        runBlocking {
            served.set(listOf(RsaKeys.generate("k1")))
            val source = JwksKeySource(uri, minRefetchInterval = Duration.ofMinutes(1))
            source.refresh()

            repeat(5) { assertNull(source.find("missing")) }
            assertEquals(1, hits.get())
        }

    @Test
    fun `unreachable JWKS keeps cache and reports failure`() =
        runBlocking {
            val source = JwksKeySource(URI.create("http://localhost:1/jwks"), requestTimeout = Duration.ofMillis(500))

            assertFalse(source.refresh())
            assertNull(source.find("k1"))
        }

    @Test
    fun `after a failed fetch an unknown kid refetches right away despite minRefetchInterval`() =
        runBlocking {
            // 기동 순서 문제 재현: 첫 조회 때 auth-api가 아직 준비되지 않았다.
            val key = RsaKeys.generate("k1")
            served.set(listOf(key))
            status.set(503)
            val source = JwksKeySource(uri, minRefetchInterval = Duration.ofMinutes(1), failureRetryInterval = Duration.ZERO)
            assertFalse(source.refresh())

            status.set(200)
            val token = JwtIssuer(key).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            assertIs<AuthResult.Success>(JwtVerifier(source).verify(token.token, TokenType.ACCESS))
            assertEquals(2, hits.get())
        }

    @Test
    fun `retries after failure are still rate limited`() =
        runBlocking {
            status.set(503)
            val source = JwksKeySource(uri, failureRetryInterval = Duration.ofMinutes(1))
            assertFalse(source.refresh())

            repeat(5) { assertNull(source.find("k1")) }
            assertEquals(1, hits.get())
        }

    @Test
    fun `concurrent misses fetch only once`() =
        runBlocking {
            served.set(listOf(RsaKeys.generate("k1")))
            val source = JwksKeySource(uri, minRefetchInterval = Duration.ZERO, failureRetryInterval = Duration.ZERO)

            val found = (1..20).map { async(Dispatchers.Default) { source.find("k1") } }.awaitAll()

            assertTrue(found.all { it != null })
            assertEquals(1, hits.get())
        }

    @Test
    fun `background loop retries a failed initial fetch with backoff instead of waiting the refresh interval`() =
        runBlocking {
            served.set(listOf(RsaKeys.generate("k1")))
            status.set(503)
            // 재조회(miss)로는 복구되지 않게 막고, 주기 루프만으로 키를 받는지 본다.
            val source =
                JwksKeySource(
                    uri,
                    refreshInterval = Duration.ofHours(1),
                    minRefetchInterval = Duration.ofHours(1),
                    failureRetryInterval = Duration.ofHours(1),
                    initialRetryBackoff = Duration.ofMillis(50),
                )
            val loop = source.start(this)
            withTimeout(2_000) { while (hits.get() < 1) delay(10) }

            status.set(200)

            withTimeout(2_000) { while (source.find("k1") == null) delay(20) }
            assertTrue(hits.get() >= 2)
            loop.cancel()
        }
}
