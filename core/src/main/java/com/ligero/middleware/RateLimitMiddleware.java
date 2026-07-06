package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.TooManyRequestsException;

import java.util.function.Function;

/**
 * Rate-limiting middleware. State lives behind {@link RateLimiterStore}
 * (in-memory token bucket by default; plug a distributed store for
 * multi-instance deployments). Keys default to the client address;
 * exceeding the limit yields {@code 429 Too Many Requests}.
 *
 * <pre>{@code
 * app.use(RateLimitMiddleware.of(100, 100)); // 100 req burst, 100 req/s refill
 * }</pre>
 */
public final class RateLimitMiddleware implements Middleware {

    private final RateLimiterStore store;
    private final Function<Context, String> keyExtractor;

    private RateLimitMiddleware(RateLimiterStore store, Function<Context, String> keyExtractor) {
        this.store = store;
        this.keyExtractor = keyExtractor;
    }

    /** Token bucket of {@code capacity} refilled at {@code refillPerSecond}, keyed by client address. */
    public static RateLimitMiddleware of(long capacity, double refillPerSecond) {
        return new RateLimitMiddleware(new TokenBucketStore(capacity, refillPerSecond),
            ctx -> String.valueOf(ctx.remoteAddress()));
    }

    /** Same, with a custom key (e.g. API key or user id). */
    public static RateLimitMiddleware of(long capacity, double refillPerSecond,
                                         Function<Context, String> keyExtractor) {
        return new RateLimitMiddleware(new TokenBucketStore(capacity, refillPerSecond), keyExtractor);
    }

    /** Custom store (e.g. Redis-backed) and key. */
    public static RateLimitMiddleware of(RateLimiterStore store,
                                         Function<Context, String> keyExtractor) {
        return new RateLimitMiddleware(store, keyExtractor);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (!store.tryAcquire(keyExtractor.apply(ctx))) {
            throw new TooManyRequestsException("Rate limit exceeded");
        }
        chain.proceed();
    }
}
