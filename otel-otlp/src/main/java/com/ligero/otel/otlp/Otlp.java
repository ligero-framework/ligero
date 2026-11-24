package com.ligero.otel.otlp;

import com.ligero.Ligero;
import com.ligero.middleware.MetricsMiddleware;
import com.ligero.middleware.TracingMiddleware;
import com.ligero.otel.OtelMetricsCollector;
import com.ligero.otel.OtelRequestLogs;
import com.ligero.otel.OtelTracer;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sends a Ligero application's traces, metrics and request logs to an
 * OpenTelemetry backend over OTLP — a collector, Grafana, Honeycomb,
 * Datadog, New Relic, ... — in one call:
 *
 * <pre>{@code
 * Ligero app = Ligero.create();
 * Otlp.instrument(app);   // first, so every request is observed
 * app.use(...).get(...);
 * app.start();
 * }</pre>
 *
 * <p>Configuration comes from the standard OpenTelemetry environment
 * variables, as with any OpenTelemetry SDK:</p>
 *
 * <pre>
 * OTEL_SERVICE_NAME=shop
 * OTEL_EXPORTER_OTLP_ENDPOINT=https://otel-collector.internal:4318
 * OTEL_EXPORTER_OTLP_HEADERS=x-api-key=...
 * OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod
 * OTEL_TRACES_SAMPLER=parentbased_traceidratio  OTEL_TRACES_SAMPLER_ARG=0.1
 * </pre>
 *
 * <p>Data goes out as {@code http/protobuf} (port 4318) through the JDK's own
 * HTTP client; the gRPC transport is not bundled. Buffered data is flushed
 * when the application stops.</p>
 */
public final class Otlp {

    private final Map<String, String> properties;
    private final boolean global;

    private Otlp(Builder builder) {
        this.properties = Map.copyOf(builder.properties);
        this.global = builder.global;
    }

    /** Configured from the {@code OTEL_*} environment, registered as the global OpenTelemetry. */
    public static OpenTelemetrySdk instrument(Ligero app) {
        return builder().instrument(app);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds the SDK without instrumenting an application. */
    public OpenTelemetrySdk sdk() {
        Map<String, String> defaults = new LinkedHashMap<>();
        // only http/protobuf is bundled; an explicit setting still wins
        defaults.put("otel.exporter.otlp.protocol", "http/protobuf");
        var auto = AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> defaults)
            .addPropertiesSupplier(() -> properties);
        if (global) {
            auto.setResultAsGlobal();
        }
        return auto.build().getOpenTelemetrySdk();
    }

    /** Tracing, metrics and request logs for every request of {@code app}; flushed on stop. */
    public OpenTelemetrySdk instrumentApp(Ligero app) {
        OpenTelemetrySdk sdk = sdk();
        app.use(new TracingMiddleware(new OtelTracer(sdk)))
           .use(new MetricsMiddleware(new OtelMetricsCollector(sdk)))
           .use(new OtelRequestLogs(sdk))
           .onStop(sdk::close);
        return sdk;
    }

    /** Settings that override the environment (mostly for tests and fixed setups). */
    public static final class Builder {
        private final Map<String, String> properties = new LinkedHashMap<>();
        private boolean global = true;

        /** {@code service.name} of the resource, overriding {@code OTEL_SERVICE_NAME}. */
        public Builder serviceName(String serviceName) {
            return property("otel.service.name", Objects.requireNonNull(serviceName, "serviceName"));
        }

        /** Where to send the data, e.g. {@code http://localhost:4318}. */
        public Builder endpoint(String endpoint) {
            return property("otel.exporter.otlp.endpoint", Objects.requireNonNull(endpoint, "endpoint"));
        }

        /**
         * Any OpenTelemetry SDK property in its system-property form, e.g.
         * {@code otel.metric.export.interval} = {@code 10000}.
         */
        public Builder property(String name, String value) {
            properties.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Whether to register the SDK as {@code GlobalOpenTelemetry} (default true). */
        public Builder global(boolean global) {
            this.global = global;
            return this;
        }

        public Otlp build() {
            return new Otlp(this);
        }

        /** Builds and instruments {@code app}. */
        public OpenTelemetrySdk instrument(Ligero app) {
            return build().instrumentApp(app);
        }
    }
}
