package com.ligero.resilience;

/** Thrown by a {@link CircuitBreaker} that is open, to fail fast. */
public final class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String message) {
        super(message);
    }
}
