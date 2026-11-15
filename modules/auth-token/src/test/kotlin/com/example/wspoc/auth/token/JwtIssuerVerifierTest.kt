package com.example.wspoc.auth.token

import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JwtIssuerVerifierTest {
    private val clock = MutableClock()
    private val key = RsaKeys.generate("k1")
    private val issuer = JwtIssuer(key, clock)
    private val verifier = JwtVerifier(StaticKeySource(key), TicketReplayGuard(clock = clock), clock)

    @Test
    fun `issued access token verifies into principal`() =
        runTest {
            val issued = issuer.issue("u1", setOf("ws:read", "ws:write"), TokenType.ACCESS, Duration.ofMinutes(15))

            val result = assertIs<AuthResult.Success>(verifier.verify(issued.token, TokenType.ACCESS))

            assertEquals(AuthPrincipal("u1", setOf("ws:read", "ws:write"), issued.expiresAt), result.principal)
        }

    @Test
    fun `expired token is rejected`() =
        runTest {
            val issued = issuer.issue("u1", emptySet(), TokenType.ACCESS, Duration.ofSeconds(30))
            clock.advance(Duration.ofSeconds(30))

            assertEquals(AuthResult.Failure(FailureReason.EXPIRED), verifier.verify(issued.token, TokenType.ACCESS))
        }

    @Test
    fun `token signed by another key with same kid is rejected`() =
        runTest {
            val forged = JwtIssuer(RsaKeys.generate("k1"), clock).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            assertEquals(AuthResult.Failure(FailureReason.BAD_SIGNATURE), verifier.verify(forged.token, TokenType.ACCESS))
        }

    @Test
    fun `token with unknown kid is rejected`() =
        runTest {
            val other = JwtIssuer(RsaKeys.generate("other"), clock).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            assertEquals(AuthResult.Failure(FailureReason.UNKNOWN_KEY), verifier.verify(other.token, TokenType.ACCESS))
        }

    @Test
    fun `tampered payload is rejected`() =
        runTest {
            val parts = issuer.issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1)).token.split('.')
            val evilPayload =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"admin","typ":"access","exp":9999999999}""".toByteArray())

            val result = verifier.verify("${parts[0]}.$evilPayload.${parts[2]}", TokenType.ACCESS)

            assertEquals(AuthResult.Failure(FailureReason.BAD_SIGNATURE), result)
        }

    @Test
    fun `garbage is malformed`() =
        runTest {
            assertEquals(AuthResult.Failure(FailureReason.MALFORMED), verifier.verify("not-a-jwt", TokenType.ACCESS))
        }

    @Test
    fun `access token cannot be used as ticket and vice versa`() =
        runTest {
            val access = issuer.issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))
            val ticket = issuer.issue("u1", emptySet(), TokenType.WS_TICKET, Duration.ofSeconds(30))

            assertEquals(AuthResult.Failure(FailureReason.WRONG_TYPE), verifier.verify(access.token, TokenType.WS_TICKET))
            assertEquals(AuthResult.Failure(FailureReason.WRONG_TYPE), verifier.verify(ticket.token, TokenType.ACCESS))
        }

    @Test
    fun `ticket is single use`() =
        runTest {
            val ticket = issuer.issue("u1", setOf("ws"), TokenType.WS_TICKET, Duration.ofSeconds(30))

            assertIs<AuthResult.Success>(verifier.verify(ticket.token, TokenType.WS_TICKET))
            assertEquals(AuthResult.Failure(FailureReason.REPLAYED), verifier.verify(ticket.token, TokenType.WS_TICKET))
        }

    @Test
    fun `ticket principal expiry is the bound session expiry, not the ticket exp`() =
        runTest {
            val access = issuer.issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(15))
            val ticket = issuer.issue("u1", emptySet(), TokenType.WS_TICKET, Duration.ofSeconds(30), sessionExpiresAt = access.expiresAt)

            val principal = assertIs<AuthResult.Success>(verifier.verify(ticket.token, TokenType.WS_TICKET)).principal

            assertEquals(access.expiresAt, principal.expiresAt)
        }

    @Test
    fun `access token can be verified repeatedly`() =
        runTest {
            val access = issuer.issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            repeat(3) { assertIs<AuthResult.Success>(verifier.verify(access.token, TokenType.ACCESS)) }
        }

    @Test
    fun `pem private key round trips`() =
        runTest {
            val pem =
                "-----BEGIN PRIVATE KEY-----\n" +
                    java.util.Base64
                        .getMimeEncoder(64, "\n".toByteArray())
                        .encodeToString(key.toPrivateKey().encoded) +
                    "\n-----END PRIVATE KEY-----\n"
            val fromPem = RsaKeys.fromPem(pem, "k1")
            val token = JwtIssuer(fromPem, clock).issue("u1", emptySet(), TokenType.ACCESS, Duration.ofMinutes(1))

            assertIs<AuthResult.Success>(verifier.verify(token.token, TokenType.ACCESS))
        }
}
