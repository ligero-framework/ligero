package com.ligero.middleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token-bucket {@link RateLimiterStore} (per-process state).
 *
 * <p>The number of tracked keys is bounded: buckets that have been idle long
 * enough to refill completely carry no information and are dropped, so a
 * client cycling through keys (spoofed addresses, random API keys) cannot
 * grow the map without limit.</p>
 */
public final class TokenBucketStore implements RateLimiterStore {

    /** Default cap on tracked keys. */
    public static final int DEFAULT_MAX_KEYS = 100_000;

    private final long capacity;
    private final double refillPerNano;
    private final int maxKeys;
    private final long fullRefillNanos;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    public TokenBucketStore(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, DEFAULT_MAX_KEYS);
    }

    /**
     * @param maxKeys upper bound on tracked keys; when it is reached and no
     *                idle bucket can be reclaimed, requests for <em>new</em>
     *                keys are rejected until space frees up
     */
    public TokenBucketStore(long capacity, double refillPerSecond, int maxKeys) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.maxKeys = maxKeys;
        this.fullRefillNanos = (long) Math.ceil(capacity / refillPerNano);
    }

    @Override
    public boolean tryAcquire(String key) {
        long now = System.nanoTime();
        sweepIfDue(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                sweep(now);
                if (buckets.size() >= maxKeys) {
                    return false; // saturated: fail closed rather than grow unbounded
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        }
        return bucket.tryAcquire();
    }

    /** Number of keys currently tracked (diagnostics, tests). */
    public int size() {
        return buckets.size();
    }

    /** Periodic cleanup, at most once per full-refill interval. */
    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last >= fullRefillNanos && lastSweepNanos.compareAndSet(last, now)) {
            sweep(now);
        }
    }

    /** Drops buckets idle for a whole refill period: they are full again. */
    private void sweep(long now) {
        buckets.values().removeIf(bucket -> bucket.idleSince(now) >= fullRefillNanos);
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

        synchronized long idleSince(long now) {
            return now - lastRefillNanos;
        }
    }
}
