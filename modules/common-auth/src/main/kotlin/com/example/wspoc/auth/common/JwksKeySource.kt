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
 * 원격 JWKS를 캐시하고 주기적으로 갱신한다. 모든 I/O는 [HttpClient.sendAsync]로 하므로
 * event loop에서 호출해도 블로킹되지 않는다. 모르는 `kid`가 오면 즉시 다시 가져오되
 * [minRefetchInterval]로 빈도를 제한한다. 그래서 키 회전을 다음 갱신 주기를 기다리지 않고 반영한다.
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

    /** [scope]에서 주기적 갱신 루프를 시작한다. 첫 조회는 즉시 한다. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                refresh()
                delay(refreshInterval.toMillis())
            }
        }

    /** 지금 JWKS를 가져온다. 실패하면 false를 반환하고 캐시된 키는 유지한다. */
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
                // 예: auth-api가 아직 안 떠 있으면 메시지가 null인 ConnectException이 난다. 캐시된 키는 유지한다.
                log.log(Level.WARNING, "JWKS refresh from $uri failed: ${e.javaClass.simpleName}: ${e.message}")
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
