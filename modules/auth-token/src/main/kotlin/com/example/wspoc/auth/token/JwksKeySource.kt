package com.example.wspoc.auth.token

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * event loop에서 호출해도 블로킹되지 않는다.
 *
 * 모르는 `kid`가 오면 즉시 다시 가져온다. 그래서 키 회전을 다음 갱신 주기를 기다리지 않고 반영한다. 재조회 빈도는 제한한다.
 *  - 마지막 조회가 **성공**했으면 [minRefetchInterval](기본 5초). 키가 정상인데 엉뚱한 `kid`가 계속 와도
 *    auth-api를 두드리지 않게 한다.
 *  - 마지막 조회가 **실패**했으면 [failureRetryInterval](기본 0.5초). 기동 순서 때문에 첫 조회가 실패해도
 *    auth-api가 뜨는 즉시 복구한다. 이전에는 5초 동안 모든 핸드셰이크가 401이었다.
 *  - 동시에 들어온 miss는 lock 안에서 다시 확인하므로 한 번만 조회한다.
 *
 * [start]의 주기 루프는 실패하면 [refreshInterval]을 기다리지 않는다. [initialRetryBackoff]부터 두 배씩,
 * 최대 [maxRetryBackoff]까지 늘리며 다시 시도한다. 핸드셰이크가 오기 전에도 키를 미리 받아 둔다.
 */
class JwksKeySource(
    private val uri: URI,
    private val refreshInterval: Duration = Duration.ofMinutes(5),
    private val minRefetchInterval: Duration = Duration.ofSeconds(5),
    private val failureRetryInterval: Duration = Duration.ofMillis(500),
    private val initialRetryBackoff: Duration = Duration.ofSeconds(1),
    private val maxRetryBackoff: Duration = Duration.ofSeconds(30),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val clock: Clock = Clock.systemUTC(),
) : KeySource {
    private val current = AtomicReference(JWKSet())

    @Volatile
    private var lastFetchAttempt: Instant = Instant.EPOCH

    @Volatile
    private var lastFetchSucceeded = false

    @Volatile
    private var lastFetchFailed = false
    private val fetchLock = Mutex()

    override suspend fun find(keyId: String?): RSAKey? {
        current.get().selectRsa(keyId)?.let { return it }
        fetchLock.withLock {
            // lock을 기다리는 동안 다른 코루틴이 이미 가져왔을 수 있다: 같은 miss로 여러 번 조회하지 않는다.
            current.get().selectRsa(keyId)?.let { return it }
            val minInterval = if (lastFetchSucceeded) minRefetchInterval else failureRetryInterval
            if (Duration.between(lastFetchAttempt, clock.instant()) >= minInterval) fetchLocked()
        }
        return current.get().selectRsa(keyId)
    }

    /** [scope]에서 주기적 갱신 루프를 시작한다. 첫 조회는 즉시 하고, 실패하면 백오프로 빨리 다시 시도한다. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            var backoff = initialRetryBackoff
            while (isActive) {
                if (refresh()) {
                    backoff = initialRetryBackoff
                    delay(refreshInterval.toMillis())
                } else {
                    delay(backoff.toMillis())
                    backoff = minOf(backoff.multipliedBy(2), maxRetryBackoff)
                }
            }
        }

    /** 지금 JWKS를 가져온다. 실패하면 false를 반환하고 캐시된 키는 유지한다. */
    suspend fun refresh(): Boolean = fetchLock.withLock { fetchLocked() }

    private suspend fun fetchLocked(): Boolean {
        lastFetchAttempt = clock.instant()
        val ok =
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
        // 실패 경고 뒤에 복구도 한 줄 남겨, 로그만 보고 언제부터 정상인지 알 수 있게 한다.
        if (ok && lastFetchFailed) log.log(Level.INFO, "JWKS refresh from $uri recovered (${current.get().keys.size} keys)")
        lastFetchSucceeded = ok
        lastFetchFailed = !ok
        return ok
    }

    private companion object {
        val log: Logger = Logger.getLogger(JwksKeySource::class.java.name)
    }
}

/**
 * 만들자마자 갱신 루프를 돌리고 [close]에서 멈추는 [JwksKeySource]. Spring 빈으로 등록하면 컨텍스트가 닫힐 때
 * `close()`가 불린다(서비스의 self 모드와 게이트웨이가 쓴다).
 */
class RefreshingJwksKeySource(
    uri: URI,
    refreshInterval: Duration,
) : KeySource,
    AutoCloseable {
    private val delegate = JwksKeySource(uri, refreshInterval)
    private val scope = CoroutineScope(SupervisorJob()).also { delegate.start(it) }

    override suspend fun find(keyId: String?) = delegate.find(keyId)

    override fun close() = scope.cancel()
}
