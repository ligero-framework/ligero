package com.ligero.otel;

import com.ligero.http.Context;
import com.ligero.middleware.TracingMiddleware;
import com.ligero.spi.Tracer;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelTracerTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();
    private final OtelTracer tracer = new OtelTracer(sdk);

    @Test
    void exportsServerSpanWithAttributes() {
        try (Tracer.Span span = tracer.startSpan("GET /users/{id}", null)) {
            span.setAttribute("http.request.method", "GET");
            span.setAttribute("http.response.status_code", 200);
            assertThat(span.traceId()).hasSize(32);
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("GET /users/{id}");
    }

    @Test
    void joinsIncomingW3cTrace() {
        String remoteTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + remoteTraceId + "-00f067aa0ba902b7-01";
        try (Tracer.Span span = tracer.startSpan("GET /x", traceparent)) {
            assertThat(span.traceId()).isEqualTo(remoteTraceId);
        }
        assertThat(exporter.getFinishedSpanItems().get(0).getParentSpanContext().getSpanId())
            .isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void tracingMiddlewareRecordsRouteStatusAndErrors() throws Exception {
        TracingMiddleware middleware = new TracingMiddleware(tracer);
        Context ok = new Context(FakeRequest.of("GET", "/users/7"), new FakeResponse(), "/", null, null);
        ok.attribute("ligero.route", "/users/{id}");
        middleware.handle(ok, () -> ok.res().status(200).send("ok"));

        Context boom = new Context(FakeRequest.of("GET", "/boom"), new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> middleware.handle(boom, () -> {
            throw new IllegalStateException("kaput");
        })).isInstanceOf(IllegalStateException.class);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getAttributes().asMap().toString()).contains("/users/{id}");
        assertThat(spans.get(1).getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(spans.get(1).getEvents()).anyMatch(e -> e.getName().equals("exception"));
        assertThat(ok.<String>attribute(TracingMiddleware.TRACE_ID_ATTRIBUTE)).hasSize(32);
    }
}
