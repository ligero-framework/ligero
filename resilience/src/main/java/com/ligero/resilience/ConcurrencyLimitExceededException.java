package com.ligero.resilience;

/** Thrown when a {@link ConcurrencyLimit} has no free slot for a call. */
public class ConcurrencyLimitExceededException extends RuntimeException {

    private final int limit;

    public ConcurrencyLimitExceededException(int limit) {
        super("More than " + limit + " concurrent calls");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
