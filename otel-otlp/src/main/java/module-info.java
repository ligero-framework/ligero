/** Ligero OTLP setup: OpenTelemetry SDK with OTLP exporters, configured from OTEL_* variables. */
module com.ligero.otel.otlp {
    requires transitive com.ligero.otel;
    requires transitive io.opentelemetry.sdk;
    requires io.opentelemetry.sdk.autoconfigure;

    exports com.ligero.otel.otlp;
}
