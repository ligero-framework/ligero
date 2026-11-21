package com.ligero.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimitTest {

    @Test
    void refusesCallsBeyondTheLimitAndReleasesSlots() throws Exception {
        ConcurrencyLimit limit = ConcurrencyLimit.of(2);
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Thread[] holders = new Thread[2];
        for (int i = 0; i < 2; i++) {
            holders[i] = Thread.ofVirtual().start(() -> limit.run(() -> {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(limit.inFlight()).isEqualTo(2);
        assertThatThrownBy(() -> limit.call(() -> "third"))
            .isInstanceOfSatisfying(ConcurrencyLimitExceededException.class, e -> assertThat(e.limit()).isEqualTo(2));

        release.countDown();
        for (Thread holder : holders) {
            holder.join();
        }
        assertThat(limit.inFlight()).isZero();
        assertThat(limit.call(() -> "now")).isEqualTo("now");
        assertThat(limit.limit()).isEqualTo(2);
    }

    @Test
    void waitingCallsGetASlotWhenOneFrees() throws Exception {
        ConcurrencyLimit limit = ConcurrencyLimit.of(1).waitAtMost(Duration.ofSeconds(5));
        CountDownLatch inside = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> limit.run(() -> {
            inside.countDown();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        inside.await();
        assertThat(limit.call(() -> "after the wait")).isEqualTo("after the wait");
        holder.join();
    }

    @Test
    void aFailingCallStillReleasesItsSlot() {
        ConcurrencyLimit limit = ConcurrencyLimit.of(1);
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limit.call(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("down");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).hasValue(3);
        assertThat(limit.inFlight()).isZero();
    }

    @Test
    void anInterruptedWaitIsRefused() {
        ConcurrencyLimit limit = ConcurrencyLimit.of(1).waitAtMost(Duration.ofSeconds(5));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> limit.run(() -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            inside.await();
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> limit.run(() -> { })).isInstanceOf(ConcurrencyLimitExceededException.class);
            assertThat(Thread.interrupted()).isTrue();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            release.countDown();
        }
        try {
            holder.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> ConcurrencyLimit.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConcurrencyLimit.of(1).waitAtMost(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
