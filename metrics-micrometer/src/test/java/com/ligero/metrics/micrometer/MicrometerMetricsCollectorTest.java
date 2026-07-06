package com.ligero.metrics.micrometer;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerMetricsCollectorTest {

    @Test
    void publishesTaggedTimers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsCollector collector = new MicrometerMetricsCollector(registry);

        collector.record("GET", "/users/{id}", 200, 5_000_000);
        collector.record("GET", "/users/{id}", 200, 7_000_000);
        collector.record("POST", "/users", 500, 1_000_000);

        Timer ok = registry.get("ligero.http.requests")
            .tags("method", "GET", "route", "/users/{id}", "status", "200").timer();
        assertThat(ok.count()).isEqualTo(2);
        assertThat(ok.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(12.0);

        Timer error = registry.get("ligero.http.requests")
            .tags("method", "POST", "status", "500").timer();
        assertThat(error.count()).isEqualTo(1);
    }
}
