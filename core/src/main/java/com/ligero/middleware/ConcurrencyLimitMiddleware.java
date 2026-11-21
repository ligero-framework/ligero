package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.HttpException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many requests are processed at the same time. Requests beyond the
 * limit wait up to {@link #waitAtMost(Duration)} (by default not at all) and
 * are otherwise answered {@code 503 Service Unavailable} with a
 * {@code Retry-After} header, so an overloaded service sheds load quickly
 * instead of queueing until every client times out.
 *
 * <pre>{@code
 * app.use(ConcurrencyLimitMiddleware.of(500));                       // the whole app
 * app.use("/reports", ConcurrencyLimitMiddleware.of(4)                // an expensive endpoint
 *     .waitAtMost(Duration.ofMillis(250)));
 * }</pre>
 *
 * <p>Unlike {@link RateLimitMiddleware}, which counts requests per client
 * over time, this counts requests in flight across all clients.</p>
 */
public final class ConcurrencyLimitMiddleware implements Middleware {

    private final int limit;
    private final Duration maxWait;
    private final Duration retryAfter;
    private final Semaphore permits;

    private ConcurrencyLimitMiddleware(int limit, Duration maxWait, Duration retryAfter) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (maxWait.isNegative() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        this.limit = limit;
        this.maxWait = maxWait;
        this.retryAfter = retryAfter;
        this.permits = new Semaphore(limit, true);
    }

    /** At most {@code limit} requests at once. */
    public static ConcurrencyLimitMiddleware of(int limit) {
        return new ConcurrencyLimitMiddleware(limit, Duration.ZERO, Duration.ofSeconds(1));
    }

    /** A copy that lets extra requests wait up to {@code maxWait} for a slot. */
    public ConcurrencyLimitMiddleware waitAtMost(Duration maxWait) {
        return new ConcurrencyLimitMiddleware(limit, Objects.requireNonNull(maxWait, "maxWait"), retryAfter);
    }

    /** A copy that suggests clients retry after {@code retryAfter} (1 s by default). */
    public ConcurrencyLimitMiddleware retryAfter(Duration retryAfter) {
        return new ConcurrencyLimitMiddleware(limit, maxWait, Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        boolean acquired = maxWait.isZero()
            ? permits.tryAcquire()
            : permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        if (!acquired) {
            ctx.header("Retry-After", String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
            throw new HttpException(503, "Too many concurrent requests; try again shortly");
        }
        try {
            chain.proceed();
        } finally {
            permits.release();
        }
    }

    /** Requests being processed right now. */
    public int inFlight() {
        return limit - permits.availablePermits();
    }
}
