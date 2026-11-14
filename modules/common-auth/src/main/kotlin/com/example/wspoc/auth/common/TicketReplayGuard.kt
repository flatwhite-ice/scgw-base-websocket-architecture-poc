package com.example.wspoc.auth.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Enforces single use of WS tickets by remembering consumed `jti`s until the ticket expires.
 * In-memory and per instance: a ticket can still be replayed against a different instance (see docs/decisions.md).
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

    /** Returns true the first time a `jti` is seen, false on any reuse. */
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
