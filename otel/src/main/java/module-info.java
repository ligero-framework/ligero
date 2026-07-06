/** Ligero Tracer adapter for OpenTelemetry. */
module com.ligero.otel {
    requires transitive com.ligero.core;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;

    exports com.ligero.otel;

    provides com.ligero.spi.Tracer with com.ligero.otel.OtelTracer;
}
