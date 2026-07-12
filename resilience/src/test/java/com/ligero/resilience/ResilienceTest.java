package com.ligero.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienceTest {

    // ---- Retry --------------------------------------------------------------

    @Test
    void retrySucceedsAfterTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        String result = Retry.of(3, Duration.ofMillis(1)).call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("flaky");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retryGivesUpAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> Retry.of(2, Duration.ofMillis(1)).exponential()
            .run(() -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("always");
            }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.get()).isEqualTo(2);
    }

    // ---- CircuitBreaker -----------------------------------------------------

    @Test
    void circuitOpensAfterThresholdThenRecovers() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(80));

        // two failures trip it
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new RuntimeException("down"); }))
                .isInstanceOf(RuntimeException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // while open it fails fast, without calling the action
        assertThatThrownBy(() -> breaker.call(() -> "unreached"))
            .isInstanceOf(CircuitOpenException.class);

        // after the open window, a successful trial closes it again
        Thread.sleep(120);
        assertThat(breaker.call(() -> "back")).isEqualTo("back");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---- Timeout ------------------------------------------------------------

    @Test
    void timeoutReturnsFastResultAndAbandonsSlowOnes() throws Exception {
        assertThat(Timeout.call(Duration.ofSeconds(1), () -> "quick")).isEqualTo("quick");

        assertThatThrownBy(() -> Timeout.call(Duration.ofMillis(50), () -> {
            Thread.sleep(1000);
            return "late";
        })).isInstanceOf(TimeoutException.class);
    }

    @Test
    void timeoutPropagatesTheTasksOwnException() {
        assertThatThrownBy(() -> Timeout.call(Duration.ofSeconds(1), () -> {
            throw new IllegalArgumentException("bad");
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
