/** Ligero adapters for OpenTelemetry: traces, metrics and request logs. */
module com.ligero.otel {
    requires transitive com.ligero.core;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;

    exports com.ligero.otel;

    provides com.ligero.spi.Tracer with com.ligero.otel.OtelTracer;
    provides com.ligero.spi.MetricsCollector with com.ligero.otel.OtelMetricsCollector;
}
