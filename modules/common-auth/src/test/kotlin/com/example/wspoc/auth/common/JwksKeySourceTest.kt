package com.example.wspoc.auth.common

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.coroutines.runBlocking
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

class JwksKeySourceTest {
    private val served = AtomicReference<List<RSAKey>>(emptyList())
    private val hits = AtomicInteger()
    private val server: DisposableServer =
        HttpServer
            .create()
            .port(0)
            .route { routes ->
                routes.get("/.well-known/jwks.json") { _, res ->
                    hits.incrementAndGet()
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
}
