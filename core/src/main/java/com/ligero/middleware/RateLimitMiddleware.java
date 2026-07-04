package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.TooManyRequestsException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory token-bucket rate limiter. Keys default to the client address;
 * exceeding the limit yields {@code 429 Too Many Requests}.
 *
 * <pre>{@code
 * app.use(RateLimitMiddleware.of(100, 100)); // 100 req burst, 100 req/s refill
 * }</pre>
 *
 * <p>State is per-process; distributed stores can be plugged in later behind
 * the same middleware (roadmap fase 3).</p>
 */
public final class RateLimitMiddleware implements Middleware {

    private final long capacity;
    private final double refillPerNano;
    private final Function<Context, String> keyExtractor;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private RateLimitMiddleware(long capacity, double refillPerSecond,
                                Function<Context, String> keyExtractor) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.keyExtractor = keyExtractor;
    }

    /** Bucket of {@code capacity} tokens refilled at {@code refillPerSecond}, keyed by client address. */
    public static RateLimitMiddleware of(long capacity, double refillPerSecond) {
        return new RateLimitMiddleware(capacity, refillPerSecond,
            ctx -> String.valueOf(ctx.remoteAddress()));
    }

    /** Same, with a custom key (e.g. API key or user id). */
    public static RateLimitMiddleware of(long capacity, double refillPerSecond,
                                         Function<Context, String> keyExtractor) {
        return new RateLimitMiddleware(capacity, refillPerSecond, keyExtractor);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String key = keyExtractor.apply(ctx);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        if (!bucket.tryAcquire()) {
            throw new TooManyRequestsException("Rate limit exceeded");
        }
        chain.proceed();
    }

    private final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(long capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * refillPerNano);
            lastRefillNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
