package com.ligero.resilience;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * A simple circuit breaker. After {@code failureThreshold} consecutive failures
 * it <b>opens</b> and fails fast (throwing {@link CircuitOpenException}) for
 * {@code openDuration}; then it goes <b>half-open</b> and lets one trial call
 * through — success closes it, another failure re-opens it.
 *
 * <pre>{@code
 * CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30));
 * String body = breaker.call(() -> http.get(url));
 * }</pre>
 *
 * <p>Thread-safe.</p>
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;

    private int consecutiveFailures;
    private long openedAt;
    private State state = State.CLOSED;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.openMillis = openDuration.toMillis();
    }

    public synchronized State state() {
        return state;
    }

    public <T> T call(Supplier<T> action) {
        acquire();
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private synchronized void acquire() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= openMillis) {
                state = State.HALF_OPEN; // allow a single trial
            } else {
                throw new CircuitOpenException("Circuit is open");
            }
        }
    }

    private synchronized void onSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    private synchronized void onFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }
}
