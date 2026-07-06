package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.spi.Tracer;

/**
 * Opens one span per request through the {@link Tracer} SPI: joins the
 * incoming W3C {@code traceparent} when present, names the span with the
 * matched route pattern (bounded cardinality) and records status/errors.
 * The trace id is exposed as the {@code traceId} attribute for logs.
 *
 * <pre>{@code
 * app.use(TracingMiddleware.fromServiceLoader()); // e.g. ligero-otel on the classpath
 * }</pre>
 */
public final class TracingMiddleware implements Middleware {

    public static final String TRACE_ID_ATTRIBUTE = "traceId";

    private final Tracer tracer;

    public TracingMiddleware(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Resolves the tracer via ServiceLoader; fails fast if no adapter is present. */
    public static TracingMiddleware fromServiceLoader() {
        return new TracingMiddleware(java.util.ServiceLoader.load(Tracer.class).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No Tracer found. Add ligero-otel (or another Tracer implementation) to the classpath.")));
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        try (Tracer.Span span = tracer.startSpan(
                ctx.method() + " " + ctx.path(), ctx.header("traceparent"))) {
            span.setAttribute("http.request.method", ctx.method());
            span.setAttribute("url.path", ctx.path());
            if (span.traceId() != null) {
                ctx.attribute(TRACE_ID_ATTRIBUTE, span.traceId());
            }
            try {
                chain.proceed();
                span.setAttribute("http.response.status_code", ctx.res().getStatus());
                String route = ctx.attribute(MetricsMiddleware.ROUTE_ATTRIBUTE);
                if (route != null) {
                    span.setAttribute("http.route", route);
                }
            } catch (Exception e) {
                span.recordError(e);
                throw e;
            }
        }
    }
}
