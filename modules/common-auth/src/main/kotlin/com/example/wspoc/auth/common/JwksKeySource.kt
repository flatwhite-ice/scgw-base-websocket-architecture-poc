package com.example.wspoc.auth.common

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Caches a remote JWKS and refreshes it periodically. All I/O goes through [HttpClient.sendAsync]
 * so callers on an event loop never block. An unknown `kid` triggers an immediate refetch,
 * rate-limited by [minRefetchInterval], so key rotation is picked up without waiting for the next refresh.
 */
class JwksKeySource(
    private val uri: URI,
    private val refreshInterval: Duration = Duration.ofMinutes(5),
    private val minRefetchInterval: Duration = Duration.ofSeconds(5),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val clock: Clock = Clock.systemUTC(),
) : KeySource {
    private val current = AtomicReference(JWKSet())

    @Volatile
    private var lastFetchAttempt: Instant = Instant.EPOCH
    private val fetchLock = Mutex()

    override suspend fun find(keyId: String?): RSAKey? {
        current.get().selectRsa(keyId)?.let { return it }
        refetchIfAllowed()
        return current.get().selectRsa(keyId)
    }

    /** Starts the periodic refresh loop in [scope]. The first fetch happens immediately. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                refresh()
                delay(refreshInterval.toMillis())
            }
        }

    /** Fetches the JWKS now. Returns false (and keeps the cached set) on failure. */
    suspend fun refresh(): Boolean =
        fetchLock.withLock {
            lastFetchAttempt = clock.instant()
            try {
                val request =
                    HttpRequest
                        .newBuilder(uri)
                        .timeout(requestTimeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build()
                val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                check(response.statusCode() == 200) { "JWKS endpoint returned ${response.statusCode()}" }
                current.set(JWKSet.parse(response.body()))
                true
            } catch (e: Exception) {
                log.log(Level.WARNING, "JWKS refresh from $uri failed: ${e.message}")
                false
            }
        }

    private suspend fun refetchIfAllowed() {
        if (Duration.between(lastFetchAttempt, clock.instant()) >= minRefetchInterval) refresh()
    }

    private companion object {
        val log: Logger = Logger.getLogger(JwksKeySource::class.java.name)
    }
}
