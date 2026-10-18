package com.unis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts requests per key in fixed time windows ("10 per minute"), in memory.
 *
 * Used by {@link RateLimitFilter} (per IP or per account, by URL) and directly
 * by AuthController (per email address, so one person's inbox can't be
 * flooded with reset emails from many IPs).
 *
 * In memory is right for one backend instance, which is what Unis runs on
 * Railway. Counts reset on a redeploy, which is harmless. If Unis ever runs
 * several instances, move the counters to Redis so they're shared.
 */
@Component
public class RateLimiter {

    /** Outcome of one check. retryAfterSeconds is 0 when allowed. */
    public record Result(boolean allowed, long retryAfterSeconds) {}

    // Longest window used anywhere is one hour; entries expire after that.
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
        .expireAfterWrite(65, TimeUnit.MINUTES)
        .maximumSize(500_000)
        .build();

    /**
     * Records one request for {@code key} and reports whether it is within
     * {@code limit} requests per {@code window}.
     */
    public Result hit(String key, int limit, Duration window) {
        long windowMs = window.toMillis();
        long now = System.currentTimeMillis();
        long windowStart = now - (now % windowMs);
        AtomicInteger count = counters.get(key + '|' + windowStart, k -> new AtomicInteger());
        int n = count.incrementAndGet();
        if (n <= limit) return new Result(true, 0);
        long retryAfter = Math.max(1, (windowStart + windowMs - now + 999) / 1000);
        return new Result(false, retryAfter);
    }
}
