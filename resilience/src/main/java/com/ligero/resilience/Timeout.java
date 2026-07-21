package com.ligero.resilience;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a task on a virtual thread and abandons it if it exceeds a deadline,
 * throwing {@link TimeoutException}. The virtual thread is interrupted so a
 * well-behaved blocking call unwinds.
 *
 * <pre>{@code
 * String body = Timeout.call(Duration.ofSeconds(2), () -> http.get(url));
 * }</pre>
 */
public final class Timeout {

    private Timeout() {
    }

    public static <T> T call(Duration limit, Callable<T> task) throws Exception {
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<T> f = executor.submit(task);
            try {
                return f.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
