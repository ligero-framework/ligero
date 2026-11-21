package com.ligero.otel;

import com.ligero.http.Context;
import com.ligero.middleware.MetricsMiddleware;
import com.ligero.middleware.Middleware;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;

import java.time.Instant;

/**
 * Emits one OpenTelemetry log record per request — the access log, sent to
 * the same backend as traces and metrics and joined to the request's trace
 * (install it after {@code TracingMiddleware} so the span is current).
 *
 * <pre>{@code
 * app.use(TracingMiddleware.fromServiceLoader())
 *    .use(new OtelRequestLogs(openTelemetry));
 * }</pre>
 *
 * <p>The record carries the method, the matched route, the path, the status
 * and the duration; 5xx responses are logged at {@code ERROR}, 4xx at
 * {@code WARN}, the rest at {@code INFO}. Query strings, headers and bodies
 * are not logged, so credentials in them do not leak into the logs.</p>
 */
public final class OtelRequestLogs implements Middleware {

    private static final AttributeKey<String> PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<Double> DURATION = AttributeKey.doubleKey("http.server.request.duration");

    private final Logger logger;

    /** Uses the global OpenTelemetry (SDK autoconfigure / agent). */
    public OtelRequestLogs() {
        this(GlobalOpenTelemetry.get());
    }

    public OtelRequestLogs(OpenTelemetry openTelemetry) {
        this.logger = openTelemetry.getLogsBridge().get("com.ligero.access");
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        long start = System.nanoTime();
        Instant at = Instant.now();
        Throwable failure = null;
        try {
            chain.proceed();
        } catch (Exception | Error e) {
            failure = e;
            throw e;
        } finally {
            // an exception still on its way to the error handler will be a 500
            int status = failure != null && ctx.res().getStatus() < 400 ? 500 : ctx.res().getStatus();
            String route = ctx.attribute(MetricsMiddleware.ROUTE_ATTRIBUTE);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Severity severity = status >= 500 ? Severity.ERROR : status >= 400 ? Severity.WARN : Severity.INFO;
            var attributes = Attributes.builder()
                .put(OtelMetricsCollector.METHOD, ctx.method())
                .put(OtelMetricsCollector.ROUTE, route != null ? route : MetricsMiddleware.UNMATCHED_ROUTE)
                .put(PATH, ctx.path())
                .put(OtelMetricsCollector.STATUS, (long) status)
                .put(DURATION, seconds);
            if (failure != null) {
                attributes.put(OtelMetricsCollector.ERROR_TYPE, failure.getClass().getName());
            }
            logger.logRecordBuilder()
                .setTimestamp(at)
                .setSeverity(severity)
                .setSeverityText(severity.name())
                .setBody(ctx.method() + " " + ctx.path() + " " + status)
                .setAllAttributes(attributes.build())
                .emit();
        }
    }
}
