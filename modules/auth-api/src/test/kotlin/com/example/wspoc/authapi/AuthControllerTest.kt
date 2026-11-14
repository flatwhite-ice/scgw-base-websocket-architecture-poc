package com.example.wspoc.authapi

import com.example.wspoc.auth.common.AuthResult
import com.example.wspoc.auth.common.FailureReason
import com.example.wspoc.auth.common.JwksKeySource
import com.example.wspoc.auth.common.JwtVerifier
import com.example.wspoc.auth.common.TokenType
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AuthControllerTest(
    @Autowired private val client: WebTestClient,
    @LocalServerPort private val port: Int,
) {
    private fun accessToken(userId: String = "u1"): TokenResponse =
        assertNotNull(
            client
                .post()
                .uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("userId" to userId))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<TokenResponse>()
                .returnResult()
                .responseBody,
        )

    private fun ticket(accessToken: String): TicketResponse =
        assertNotNull(
            client
                .post()
                .uri("/auth/ws-ticket")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<TicketResponse>()
                .returnResult()
                .responseBody,
        )

    private fun jwksVerifier() = JwtVerifier(JwksKeySource(URI.create("http://localhost:$port/.well-known/jwks.json")))

    @Test
    fun `token and ticket issued by the API verify against its JWKS`() =
        runBlocking {
            val access = accessToken("alice")
            val ticket = ticket(access.token)
            val verifier = jwksVerifier()

            val accessPrincipal = assertIs<AuthResult.Success>(verifier.verify(access.token, TokenType.ACCESS)).principal
            val ticketPrincipal = assertIs<AuthResult.Success>(verifier.verify(ticket.ticket, TokenType.WS_TICKET)).principal

            assertEquals("alice", accessPrincipal.userId)
            assertEquals(setOf("ws"), accessPrincipal.scopes)
            assertEquals("alice", ticketPrincipal.userId)
            assertEquals(access.expiresAt, ticketPrincipal.expiresAt, "WS session is bound to the access token expiry")
            assertEquals(FailureReason.REPLAYED, (verifier.verify(ticket.ticket, TokenType.WS_TICKET) as AuthResult.Failure).reason)
        }

    @Test
    fun `ws-ticket requires a valid access token`() {
        client
            .post()
            .uri("/auth/ws-ticket")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .post()
            .uri("/auth/ws-ticket")
            .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a ticket cannot be exchanged for another ticket`() {
        val ticket = ticket(accessToken().token)

        client
            .post()
            .uri("/auth/ws-ticket")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${ticket.ticket}")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `blank userId is rejected`() {
        client
            .post()
            .uri("/auth/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("userId" to " "))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `ttlSeconds shortens the access token and is bounded by the default ttl`() {
        val before = Instant.now()
        val short =
            assertNotNull(
                client
                    .post()
                    .uri("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(mapOf("userId" to "short", "ttlSeconds" to 10))
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody<TokenResponse>()
                    .returnResult()
                    .responseBody,
            )
        val ttl = Duration.between(before, short.expiresAt).seconds
        assertTrue(ttl in 8..11, "ttl=$ttl")

        for (bad in listOf(0, 15 * 60 + 1)) {
            client
                .post()
                .uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("userId" to "short", "ttlSeconds" to bad))
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `jwks exposes only the public key`() {
        client
            .get()
            .uri("/.well-known/jwks.json")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.keys[0].kid")
            .isEqualTo("poc-1")
            .jsonPath("$.keys[0].kty")
            .isEqualTo("RSA")
            .jsonPath("$.keys[0].d")
            .doesNotExist()
    }
}
