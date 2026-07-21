package com.ligero.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTest {

    @Test
    void fixedRateRunsRepeatedlyOnVirtualThreads() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            CountDownLatch latch = new CountDownLatch(3);
            boolean[] onVirtual = {true};
            scheduler.fixedRate(Duration.ofMillis(20), () -> {
                onVirtual[0] &= Thread.currentThread().isVirtual();
                latch.countDown();
            });
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(onVirtual[0]).isTrue();
        }
    }

    @Test
    void fixedDelayRunsAgainAfterEachCompletion() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            CountDownLatch latch = new CountDownLatch(3);
            scheduler.fixedDelay(Duration.ofMillis(20), latch::countDown);
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void onceRunsExactlyOnce() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            CountDownLatch latch = new CountDownLatch(1);
            scheduler.once(Duration.ofMillis(10), latch::countDown);
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aThrowingTaskDoesNotCancelTheSchedule() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(3);
            scheduler.fixedRate(Duration.ofMillis(20), () -> {
                runs.incrementAndGet();
                latch.countDown();
                throw new RuntimeException("boom");
            });
            // despite throwing every time, it keeps firing
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void cancelStopsFutureRuns() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            AtomicInteger runs = new AtomicInteger();
            ScheduledTask task = scheduler.fixedRate(Duration.ofMillis(20), runs::incrementAndGet);
            Thread.sleep(120);
            task.cancel();
            assertThat(task.isCancelled()).isTrue();
            int afterCancel = runs.get();
            Thread.sleep(120);
            assertThat(runs.get()).isEqualTo(afterCancel); // no further runs
        }
    }

    @Test
    void dailyAtSchedulesWithoutRunningImmediately() throws Exception {
        try (Scheduler scheduler = new Scheduler()) {
            AtomicInteger runs = new AtomicInteger();
            // a time ~1 minute in the future must not fire during the test window
            LocalTime soon = LocalTime.now().plusMinutes(1);
            scheduler.dailyAt(soon, ZoneId.systemDefault(), runs::incrementAndGet);
            Thread.sleep(100);
            assertThat(runs.get()).isZero();
        }
    }
}
