package com.ligero.otel;

import com.ligero.spi.Tracer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The no-arg (ServiceLoader) constructor falls back to the global OpenTelemetry,
 * which is a no-op unless an SDK/agent is installed. A no-op span has no valid
 * trace context, so {@code traceId()} is null — exercised here so the fallback
 * path is covered without an SDK on the classpath.
 */
class OtelTracerNoopTest {

    @Test
    void noArgConstructorProducesNoopSpansWithoutTraceId() {
        OtelTracer tracer = new OtelTracer(); // global no-op
        try (Tracer.Span span = tracer.startSpan("GET /noop", null)) {
            span.setAttribute("http.request.method", "GET");
            span.setAttribute("http.response.status_code", 200);
            assertThat(span.traceId()).isNull();
        }
    }
}
