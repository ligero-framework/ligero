package com.ligero.otel;

import com.ligero.spi.Tracer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

/**
 * {@link Tracer} adapter over the OpenTelemetry API. Vendor-neutral by
 * design: point your OTel SDK/agent at any backend (OTLP collector,
 * New Relic, Datadog, Jaeger, ...) and Ligero spans flow there.
 *
 * <p>Discovered via ServiceLoader using {@link GlobalOpenTelemetry} (works
 * out of the box with the OTel Java agent), or construct it with an explicit
 * {@link OpenTelemetry} instance from your SDK setup.</p>
 */
public final class OtelTracer implements Tracer {

    private static final TextMapGetter<String> TRACEPARENT_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return java.util.List.of("traceparent");
        }

        @Override
        public String get(String carrier, String key) {
            return "traceparent".equalsIgnoreCase(key) ? carrier : null;
        }
    };

    private final OpenTelemetry openTelemetry;

    /** ServiceLoader constructor: uses the global OTel (SDK autoconfigure / agent). */
    public OtelTracer() {
        this(GlobalOpenTelemetry.get());
    }

    public OtelTracer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public Tracer.Span startSpan(String name, String traceparent) {
        io.opentelemetry.api.trace.Tracer tracer = openTelemetry.getTracer("com.ligero");
        Context parent = traceparent == null
            ? Context.current()
            : openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), traceparent, TRACEPARENT_GETTER);
        io.opentelemetry.api.trace.Span otelSpan = tracer.spanBuilder(name)
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .startSpan();
        return new OtelSpan(otelSpan);
    }

    private record OtelSpan(io.opentelemetry.api.trace.Span span) implements Tracer.Span {

        @Override
        public Tracer.Span setAttribute(String key, String value) {
            span.setAttribute(key, value);
            return this;
        }

        @Override
        public Tracer.Span setAttribute(String key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        @Override
        public Tracer.Span recordError(Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR);
            return this;
        }

        @Override
        public String traceId() {
            return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        }

        @Override
        public void close() {
            span.end();
        }
    }
}
