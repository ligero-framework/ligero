package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.TooManyRequestsException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitMiddlewareTest {

    private static Context context(String address) {
        return new Context(FakeRequest.of("GET", "/").remoteAddress(address),
            new FakeResponse(), "/", null, null);
    }

    @Test
    void allowsRequestsWithinCapacity() throws Exception {
        RateLimitMiddleware limiter = RateLimitMiddleware.of(3, 0.000001);
        AtomicInteger served = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            limiter.handle(context("10.0.0.1"), served::incrementAndGet);
        }
        assertThat(served.get()).isEqualTo(3);
    }

    @Test
    void rejectsWhenBucketIsEmpty() throws Exception {
        RateLimitMiddleware limiter = RateLimitMiddleware.of(1, 0.000001);
        limiter.handle(context("10.0.0.1"), () -> { });

        assertThatThrownBy(() -> limiter.handle(context("10.0.0.1"), () -> { }))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void tracksClientsIndependently() throws Exception {
        RateLimitMiddleware limiter = RateLimitMiddleware.of(1, 0.000001);
        limiter.handle(context("10.0.0.1"), () -> { });

        AtomicInteger served = new AtomicInteger();
        limiter.handle(context("10.0.0.2"), served::incrementAndGet);
        assertThat(served.get()).isEqualTo(1);
    }
}
