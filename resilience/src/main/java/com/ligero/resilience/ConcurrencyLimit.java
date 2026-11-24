package com.ligero.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many calls run at the same time (a bulkhead). Calls beyond the
 * limit wait up to {@link #waitAtMost(Duration)} for a slot — by default
 * they do not wait — and otherwise fail fast with
 * {@link ConcurrencyLimitExceededException}, so a slow dependency cannot tie
 * up every thread of the application.
 *
 * <pre>{@code
 * ConcurrencyLimit payments = ConcurrencyLimit.of(20).waitAtMost(Duration.ofMillis(200));
 * Receipt receipt = payments.call(() -> gateway.charge(order));
 * }</pre>
 *
 * <p>With virtual threads, the number of threads is no longer what runs out
 * — the dependency is. Size the limit to what it can take (its connection
 * pool, its rate limit), and share one instance per dependency.</p>
 */
public final class ConcurrencyLimit {

    private final int limit;
    private final Duration maxWait;
    private final Semaphore permits;

    private ConcurrencyLimit(int limit, Duration maxWait) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative");
        }
        this.limit = limit;
        this.maxWait = maxWait;
        this.permits = new Semaphore(limit, true);
    }

    /** At most {@code limit} calls at once; extra calls fail immediately. */
    public static ConcurrencyLimit of(int limit) {
        return new ConcurrencyLimit(limit, Duration.ZERO);
    }

    /** A copy that lets extra calls wait up to {@code maxWait} for a slot. Use it before sharing the limit. */
    public ConcurrencyLimit waitAtMost(Duration maxWait) {
        return new ConcurrencyLimit(limit, Objects.requireNonNull(maxWait, "maxWait"));
    }

    /** Runs {@code task} within the limit. */
    public <T> T call(Callable<T> task) throws Exception {
        acquire();
        try {
            return task.call();
        } finally {
            permits.release();
        }
    }

    /** Runs {@code task} within the limit. */
    public void run(Runnable task) {
        try {
            acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyLimitExceededException(limit);
        }
        try {
            task.run();
        } finally {
            permits.release();
        }
    }

    private void acquire() throws InterruptedException {
        boolean acquired = maxWait.isZero()
            ? permits.tryAcquire()
            : permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        if (!acquired) {
            throw new ConcurrencyLimitExceededException(limit);
        }
    }

    /** The configured maximum. */
    public int limit() {
        return limit;
    }

    /** Calls running right now. */
    public int inFlight() {
        return limit - permits.availablePermits();
    }
}
