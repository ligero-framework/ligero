package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsMiddlewareTest {

    @Test
    void recordsMatchedRouteStatusAndDuration() throws Exception {
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector();
        Context ctx = new Context(FakeRequest.of("GET", "/users/42"), new FakeResponse(), "/", null, null);
        ctx.attribute(MetricsMiddleware.ROUTE_ATTRIBUTE, "/users/{id}");

        new MetricsMiddleware(collector).handle(ctx, () -> ctx.status(200).res().send("ok"));

        var metrics = collector.snapshot().get("GET /users/{id}");
        assertThat(metrics.count()).isEqualTo(1);
        assertThat(metrics.errors()).isZero();
        assertThat(metrics.totalNanos()).isPositive();
    }

    @Test
    void countsServerErrorsEvenWhenChainThrows() {
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector();
        Context ctx = new Context(FakeRequest.of("GET", "/boom"), new FakeResponse(), "/", null, null);
        ctx.res().status(500);

        assertThatThrownBy(() -> new MetricsMiddleware(collector)
                .handle(ctx, () -> { throw new IllegalStateException("boom"); }))
            .isInstanceOf(IllegalStateException.class);

        var metrics = collector.snapshot().get("GET /boom");
        assertThat(metrics.count()).isEqualTo(1);
        assertThat(metrics.errors()).isEqualTo(1);
    }
}
