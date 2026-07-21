package com.ligero.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Retries an action a bounded number of times, sleeping between attempts.
 * Backoff is fixed by default; {@link #exponential()} doubles it each attempt.
 *
 * <pre>{@code
 * String body = Retry.of(3, Duration.ofMillis(200)).exponential()
 *     .call(() -> http.get(url));
 * }</pre>
 */
public final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);

    private final int maxAttempts;
    private final Duration backoff;
    private final boolean exponential;

    private Retry(int maxAttempts, Duration backoff, boolean exponential) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.exponential = exponential;
    }

    public static Retry of(int maxAttempts, Duration backoff) {
        return new Retry(maxAttempts, backoff, false);
    }

    /** Doubles the backoff after each failed attempt. */
    public Retry exponential() {
        return new Retry(maxAttempts, backoff, true);
    }

    /** Runs {@code action}, retrying on any {@link RuntimeException}. */
    public <T> T call(Supplier<T> action) {
        RuntimeException last = null;
        long sleepMillis = backoff.toMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == maxAttempts) {
                    break;
                }
                log.debug("Attempt {}/{} failed ({}); retrying", attempt, maxAttempts, e.toString());
                sleep(sleepMillis);
                if (exponential) {
                    sleepMillis *= 2;
                }
            }
        }
        throw last;
    }

    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }
}
