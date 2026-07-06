package com.ligero.spi;

/**
 * SPI for distributed tracing so the core stays vendor-neutral: the client
 * picks the backend by adding an adapter module ({@code ligero-otel} for
 * OpenTelemetry today; New Relic, Datadog, etc. can implement the same
 * interface or be fed through an OTLP exporter).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} or injected explicitly
 * into {@code TracingMiddleware}.</p>
 */
public interface Tracer {

    /**
     * Starts a span for one server request.
     *
     * @param name        low-cardinality name (e.g. {@code GET /users/{id}})
     * @param traceparent incoming W3C {@code traceparent} header, or null —
     *                    implementations should join the remote trace when present
     */
    Span startSpan(String name, String traceparent);

    /** One in-flight span. */
    interface Span extends AutoCloseable {

        Span setAttribute(String key, String value);

        Span setAttribute(String key, long value);

        /** Marks the span as failed and records the throwable. */
        Span recordError(Throwable error);

        /** Trace id of this span (for log correlation), or null. */
        String traceId();

        @Override
        void close();
    }
}
