package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.HttpException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimitMiddlewareTest {

    private static Context ctx(FakeResponse response) {
        return new Context(FakeRequest.of("GET", "/reports"), response, "/", null, null);
    }

    @Test
    void shedsRequestsBeyondTheLimitWith503AndRetryAfter() throws Exception {
        ConcurrencyLimitMiddleware limit = ConcurrencyLimitMiddleware.of(1).retryAfter(Duration.ofMillis(2500));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread busy = Thread.ofVirtual().start(() -> {
            try {
                limit.handle(ctx(new FakeResponse()), () -> {
                    inside.countDown();
                    release.await();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(limit.inFlight()).isEqualTo(1);

        FakeResponse refused = new FakeResponse();
        assertThatThrownBy(() -> limit.handle(ctx(refused), () -> { }))
            .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.getStatus()).isEqualTo(503));
        assertThat(refused.headerValue("Retry-After")).isEqualTo("3");

        release.countDown();
        busy.join();
        boolean[] served = new boolean[1];
        limit.handle(ctx(new FakeResponse()), () -> served[0] = true);
        assertThat(served[0]).isTrue();
        assertThat(limit.inFlight()).isZero();
    }

    @Test
    void waitsForASlotWhenAsked() throws Exception {
        ConcurrencyLimitMiddleware limit = ConcurrencyLimitMiddleware.of(1).waitAtMost(Duration.ofSeconds(5));
        CountDownLatch inside = new CountDownLatch(1);
        Thread busy = Thread.ofVirtual().start(() -> {
            try {
                limit.handle(ctx(new FakeResponse()), () -> {
                    inside.countDown();
                    Thread.sleep(100);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        inside.await();
        boolean[] served = new boolean[1];
        limit.handle(ctx(new FakeResponse()), () -> served[0] = true);
        assertThat(served[0]).isTrue();
        busy.join();
    }

    @Test
    void aFailingRequestReleasesItsSlot() {
        ConcurrencyLimitMiddleware limit = ConcurrencyLimitMiddleware.of(1);
        assertThatThrownBy(() -> limit.handle(ctx(new FakeResponse()), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(limit.inFlight()).isZero();
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> ConcurrencyLimitMiddleware.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConcurrencyLimitMiddleware.of(1).retryAfter(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
