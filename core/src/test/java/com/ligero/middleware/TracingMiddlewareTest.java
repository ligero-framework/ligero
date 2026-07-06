package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.spi.Tracer;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingMiddlewareTest {

    /** Recording tracer double: proves the middleware works with ANY vendor. */
    static final class FakeTracer implements Tracer {
        final List<String> events = new ArrayList<>();
        String lastTraceparent;

        @Override
        public Span startSpan(String name, String traceparent) {
            events.add("start " + name);
            lastTraceparent = traceparent;
            return new Span() {
                @Override
                public Span setAttribute(String key, String value) {
                    events.add(key + "=" + value);
                    return this;
                }

                @Override
                public Span setAttribute(String key, long value) {
                    events.add(key + "=" + value);
                    return this;
                }

                @Override
                public Span recordError(Throwable error) {
                    events.add("error " + error.getMessage());
                    return this;
                }

                @Override
                public String traceId() {
                    return "t".repeat(32);
                }

                @Override
                public void close() {
                    events.add("end");
                }
            };
        }
    }

    @Test
    void opensSpanSetsAttributesAndExposesTraceId() throws Exception {
        FakeTracer tracer = new FakeTracer();
        FakeRequest request = FakeRequest.of("GET", "/users/9")
            .header("traceparent", "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        ctx.attribute(MetricsMiddleware.ROUTE_ATTRIBUTE, "/users/{id}");

        new TracingMiddleware(tracer).handle(ctx, () -> ctx.res().status(200).send("ok"));

        assertThat(tracer.lastTraceparent).startsWith("00-");
        assertThat(tracer.events).contains("start GET /users/9",
            "http.request.method=GET", "http.response.status_code=200",
            "http.route=/users/{id}", "end");
        assertThat(ctx.<String>attribute(TracingMiddleware.TRACE_ID_ATTRIBUTE)).hasSize(32);
    }

    @Test
    void recordsErrorsAndStillClosesSpan() {
        FakeTracer tracer = new FakeTracer();
        Context ctx = new Context(FakeRequest.of("GET", "/boom"), new FakeResponse(), "/", null, null);

        assertThatThrownBy(() -> new TracingMiddleware(tracer).handle(ctx, () -> {
            throw new IllegalStateException("kaput");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tracer.events).contains("error kaput", "end");
    }

    @Test
    void serviceLoaderFactoryFailsWithGuidanceWhenNoAdapter() {
        assertThatThrownBy(TracingMiddleware::fromServiceLoader)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ligero-otel");
    }
}
