package com.ligero.otel;

import com.ligero.spi.MetricsCollector;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;

import java.util.List;

/**
 * {@link MetricsCollector} that records requests as OpenTelemetry metrics,
 * named and labelled after the OpenTelemetry HTTP semantic conventions:
 *
 * <ul>
 *   <li>{@code http.server.request.duration} — histogram, seconds;</li>
 *   <li>attributes {@code http.request.method}, {@code http.route},
 *       {@code http.response.status_code} and, for 5xx responses,
 *       {@code error.type}.</li>
 * </ul>
 *
 * <pre>{@code
 * app.use(new MetricsMiddleware(new OtelMetricsCollector(openTelemetry)));
 * }</pre>
 *
 * <p>Any OpenTelemetry SDK or agent exports them — over OTLP to a collector,
 * Prometheus, Grafana, Datadog, New Relic, ... See {@code ligero-otel-otlp}
 * for a ready-made OTLP setup.</p>
 */
public final class OtelMetricsCollector implements MetricsCollector {

    static final AttributeKey<String> METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> ROUTE = AttributeKey.stringKey("http.route");
    static final AttributeKey<Long> STATUS = AttributeKey.longKey("http.response.status_code");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    /** The bucket boundaries the semantic conventions recommend for request durations. */
    private static final List<Double> BUCKETS =
        List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);

    private final DoubleHistogram duration;
    private final LongCounter requests;

    /** ServiceLoader constructor: uses the global OpenTelemetry (SDK autoconfigure / agent). */
    public OtelMetricsCollector() {
        this(GlobalOpenTelemetry.get());
    }

    public OtelMetricsCollector(OpenTelemetry openTelemetry) {
        var meter = openTelemetry.getMeter("com.ligero");
        this.duration = meter.histogramBuilder("http.server.request.duration")
            .setUnit("s")
            .setDescription("Duration of HTTP server requests.")
            .setExplicitBucketBoundariesAdvice(BUCKETS)
            .build();
        this.requests = meter.counterBuilder("ligero.server.requests")
            .setUnit("{request}")
            .setDescription("HTTP requests served, by route and status.")
            .build();
    }

    @Override
    public void record(String method, String route, int status, long durationNanos) {
        var attributes = Attributes.builder()
            .put(METHOD, method)
            .put(ROUTE, route)
            .put(STATUS, (long) status);
        if (status >= 500) {
            attributes.put(ERROR_TYPE, String.valueOf(status));
        }
        Attributes built = attributes.build();
        duration.record(durationNanos / 1_000_000_000.0, built);
        requests.add(1, built);
    }
}
