package com.ligero.middleware;

/**
 * SPI for rate-limit state so distributed stores (Redis, etc.) can replace
 * the built-in in-memory token bucket without touching the middleware.
 */
public interface RateLimiterStore {

    /** Attempts to consume one permit for the key; false means "throttle". */
    boolean tryAcquire(String key);
}
