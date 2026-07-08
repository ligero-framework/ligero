package com.ligero.redis;

import com.ligero.middleware.RateLimiterStore;

import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Distributed {@link RateLimiterStore}: a fixed-window counter in Redis, so a
 * limit of "N requests per window" is shared across every app instance. Each
 * window is one atomic {@code INCR} keyed by {@code ratelimit:<key>:<window>};
 * the key expires with the window, so old buckets clean themselves up.
 *
 * <pre>{@code
 * RateLimiterStore limiter =
 *     RedisRateLimiterStore.usingJedis(pool, 100, Duration.ofMinutes(1));
 * app.use(RateLimitMiddleware.of(limiter));   // 100 req/min per client, cluster-wide
 * }</pre>
 */
public final class RedisRateLimiterStore implements RateLimiterStore {

    private final RedisOps ops;
    private final int limit;
    private final long windowSeconds;
    private final LongSupplier epochSeconds;

    public RedisRateLimiterStore(RedisOps ops, int limit, Duration window) {
        this(ops, limit, window, () -> Instant.now().getEpochSecond());
    }

    RedisRateLimiterStore(RedisOps ops, int limit, Duration window, LongSupplier epochSeconds) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.ops = ops;
        this.limit = limit;
        this.windowSeconds = window.toSeconds();
        this.epochSeconds = epochSeconds;
    }

    /** Convenience factory over a Jedis pool. */
    public static RedisRateLimiterStore usingJedis(JedisPool pool, int limit, Duration window) {
        return new RedisRateLimiterStore(new JedisRedisOps(pool), limit, window);
    }

    @Override
    public boolean tryAcquire(String key) {
        long window = epochSeconds.getAsLong() / windowSeconds;
        long count = ops.incrementWithTtl("ratelimit:" + key + ":" + window, windowSeconds);
        return count <= limit;
    }
}
