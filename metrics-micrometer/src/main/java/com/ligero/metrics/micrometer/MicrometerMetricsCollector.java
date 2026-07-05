package com.ligero.metrics.micrometer;

import com.ligero.spi.MetricsCollector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * {@link MetricsCollector} adapter publishing per-route request timers to a
 * Micrometer {@link MeterRegistry} (Prometheus, Datadog, etc.).
 *
 * <pre>{@code
 * MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
 * app.use(new MetricsMiddleware(new MicrometerMetricsCollector(registry)));
 * }</pre>
 */
public final class MicrometerMetricsCollector implements MetricsCollector {

    private final MeterRegistry registry;

    public MicrometerMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(String method, String route, int status, long durationNanos) {
        Timer.builder("ligero.http.requests")
            .tag("method", method)
            .tag("route", route)
            .tag("status", String.valueOf(status))
            .register(registry)
            .record(Duration.ofNanos(durationNanos));
    }
}
