package com.ligero.middleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory token-bucket {@link RateLimiterStore} (per-process state). */
public final class TokenBucketStore implements RateLimiterStore {

    private final long capacity;
    private final double refillPerNano;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketStore(long capacity, double refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
    }

    @Override
    public boolean tryAcquire(String key) {
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity)).tryAcquire();
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
