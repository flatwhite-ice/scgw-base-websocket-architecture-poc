package com.example.wspoc.auth.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 사용된 `jti`를 티켓 만료 시각까지 기억해서 WS 티켓을 1회만 쓸 수 있게 한다.
 * 인메모리이고 인스턴스마다 따로라서, 다른 인스턴스에서는 같은 티켓을 다시 쓸 수 있다(docs/decisions.md 참고).
 */
class TicketReplayGuard(
    maximumSize: Long = 1_000_000,
    private val clock: Clock = Clock.systemUTC(),
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val consumed: Cache<String, Instant> =
        Caffeine
            .newBuilder()
            .maximumSize(maximumSize)
            .ticker(ticker)
            .expireAfter(UntilTicketExpiry())
            .build()

    /** 처음 보는 `jti`면 true, 재사용이면 false를 반환한다. */
    fun tryConsume(
        jti: String,
        expiresAt: Instant,
    ): Boolean = consumed.asMap().putIfAbsent(jti, expiresAt) == null

    private inner class UntilTicketExpiry : Expiry<String, Instant> {
        override fun expireAfterCreate(
            key: String,
            value: Instant,
            currentTime: Long,
        ): Long = Duration.between(clock.instant(), value).coerceAtLeast(Duration.ofSeconds(1)).toNanos()

        override fun expireAfterUpdate(
            key: String,
            value: Instant,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration

        override fun expireAfterRead(
            key: String,
            value: Instant,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration
    }
}
