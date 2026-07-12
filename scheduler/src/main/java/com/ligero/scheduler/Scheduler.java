package com.ligero.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A tiny, dependency-free scheduler for background tasks. Timing is driven by a
 * small daemon thread pool, but every task runs on its own <b>virtual thread</b>
 * — so a slow job never stalls the timer, and a job that throws never cancels
 * its own schedule (the exception is logged and the schedule keeps ticking).
 *
 * <pre>{@code
 * try (Scheduler scheduler = new Scheduler()) {
 *     scheduler.fixedRate(Duration.ofMinutes(5), () -> cache.evictExpired());
 *     scheduler.dailyAt(LocalTime.of(3, 0), ZoneId.systemDefault(), reports::nightly);
 * } // close() stops everything
 * }</pre>
 *
 * <p>Register it as a bean and the {@code Beans} container closes it on shutdown,
 * or manage it yourself with try-with-resources.</p>
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ScheduledExecutorService timer;

    /** A scheduler with a single timer thread (tasks still run on virtual threads). */
    public Scheduler() {
        this(1);
    }

    public Scheduler(int timerThreads) {
        this.timer = Executors.newScheduledThreadPool(timerThreads, r -> {
            Thread t = new Thread(r, "ligero-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs {@code task} every {@code period}, measured from each start time. */
    public ScheduledTask fixedRate(Duration period, Runnable task) {
        long ms = period.toMillis();
        ScheduledFuture<?> future =
            timer.scheduleAtFixedRate(() -> runAsync(task), ms, ms, MILLISECONDS);
        return new ScheduledTask(future);
    }

    /** Runs {@code task} repeatedly, waiting {@code delay} <i>between</i> completions. */
    public ScheduledTask fixedDelay(Duration delay, Runnable task) {
        long ms = delay.toMillis();
        ScheduledTask handle = new ScheduledTask();
        Runnable loop = new Runnable() {
            @Override
            public void run() {
                Thread.startVirtualThread(() -> {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        log.error("Scheduled task failed", t);
                    } finally {
                        if (!handle.isCancelled()) {
                            handle.reset(timer.schedule(this, ms, MILLISECONDS));
                        }
                    }
                });
            }
        };
        handle.reset(timer.schedule(loop, ms, MILLISECONDS));
        return handle;
    }

    /** Runs {@code task} once, {@code after} the given delay. */
    public ScheduledTask once(Duration after, Runnable task) {
        return new ScheduledTask(timer.schedule(() -> runAsync(task), after.toMillis(), MILLISECONDS));
    }

    /** Runs {@code task} every day at {@code time} in the given zone. */
    public ScheduledTask dailyAt(LocalTime time, ZoneId zone, Runnable task) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(time);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long initialDelay = Duration.between(now, next).toMillis();
        ScheduledFuture<?> future = timer.scheduleAtFixedRate(
            () -> runAsync(task), initialDelay, Duration.ofDays(1).toMillis(), MILLISECONDS);
        return new ScheduledTask(future);
    }

    private void runAsync(Runnable task) {
        Thread.startVirtualThread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("Scheduled task failed", t);
            }
        });
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
