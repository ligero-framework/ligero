package com.ligero.scheduler;

import java.util.concurrent.ScheduledFuture;

/**
 * A handle to a scheduled task. {@link #cancel()} stops future runs; for a
 * {@code fixedDelay} task the underlying future is replaced after each run, so
 * the handle tracks the current one.
 */
public final class ScheduledTask {

    private volatile ScheduledFuture<?> future;
    private volatile boolean cancelled;

    ScheduledTask() {
    }

    ScheduledTask(ScheduledFuture<?> future) {
        this.future = future;
    }

    void reset(ScheduledFuture<?> next) {
        this.future = next;
        if (cancelled) {
            next.cancel(false);
        }
    }

    /** Cancels future executions. A run already in flight is not interrupted. */
    public void cancel() {
        cancelled = true;
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
