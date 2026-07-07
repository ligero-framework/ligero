package com.ligero.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisRateLimiterStoreTest {

    @Test
    void allowsUpToTheLimitThenThrottlesWithinAWindow() {
        RedisRateLimiterStore limiter = new RedisRateLimiterStore(
            new FakeRedisOps(), 3, Duration.ofMinutes(1), () -> 1000L);

        assertThat(limiter.tryAcquire("ip")).isTrue();   // 1
        assertThat(limiter.tryAcquire("ip")).isTrue();   // 2
        assertThat(limiter.tryAcquire("ip")).isTrue();   // 3
        assertThat(limiter.tryAcquire("ip")).isFalse();  // 4 -> throttled
    }

    @Test
    void countsPerKeyIndependently() {
        RedisRateLimiterStore limiter = new RedisRateLimiterStore(
            new FakeRedisOps(), 1, Duration.ofMinutes(1), () -> 1000L);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();    // different key, own bucket
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void resetsWhenTheWindowRolls() {
        AtomicLong clock = new AtomicLong(0);
        RedisRateLimiterStore limiter = new RedisRateLimiterStore(
            new FakeRedisOps(), 1, Duration.ofSeconds(10), clock::get);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();  // same 10s window
        clock.set(10);                                    // next window
        assertThat(limiter.tryAcquire("ip")).isTrue();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RedisRateLimiterStore(new FakeRedisOps(), 0, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRateLimiterStore(new FakeRedisOps(), 1, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
