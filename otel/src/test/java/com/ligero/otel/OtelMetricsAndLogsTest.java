package com.ligero.otel;

import com.ligero.http.Context;
import com.ligero.middleware.MetricsMiddleware;
import com.ligero.middleware.TracingMiddleware;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelMetricsAndLogsTest {

    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
        .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build();

    private static Context ctx(String path, String route) {
        Context ctx = new Context(FakeRequest.of("GET", path + "?token=secret"), new FakeResponse(), "/", null, null);
        if (route != null) {
            ctx.attribute(MetricsMiddleware.ROUTE_ATTRIBUTE, route);
        }
        return ctx;
    }

    @Test
    void recordsTheSemanticConventionHistogram() throws Exception {
        MetricsMiddleware middleware = new MetricsMiddleware(new OtelMetricsCollector(sdk));
        for (int i = 0; i < 3; i++) {
            Context ok = ctx("/users/" + i, "/users/{id}");
            middleware.handle(ok, () -> ok.res().status(200));
        }
        Context failed = ctx("/users/9", "/users/{id}");
        middleware.handle(failed, () -> failed.res().status(503));
        Context scan = ctx("/wp-admin.php", null);
        middleware.handle(scan, () -> scan.res().status(404));

        MetricData duration = metrics.collectAllMetrics().stream()
            .filter(metric -> metric.getName().equals("http.server.request.duration")).findFirst().orElseThrow();
        assertThat(duration.getUnit()).isEqualTo("s");
        List<HistogramPointData> points = List.copyOf(duration.getHistogramData().getPoints());
        assertThat(points).hasSize(3);
        HistogramPointData ok = points.stream()
            .filter(p -> Long.valueOf(200).equals(p.getAttributes().get(OtelMetricsCollector.STATUS))).findFirst().orElseThrow();
        assertThat(ok.getCount()).isEqualTo(3);
        assertThat(ok.getAttributes().get(OtelMetricsCollector.ROUTE)).isEqualTo("/users/{id}");
        assertThat(ok.getAttributes().get(OtelMetricsCollector.METHOD)).isEqualTo("GET");
        assertThat(ok.getAttributes().get(OtelMetricsCollector.ERROR_TYPE)).isNull();
        assertThat(ok.getBoundaries()).startsWith(0.005, 0.01);
        HistogramPointData error = points.stream()
            .filter(p -> Long.valueOf(503).equals(p.getAttributes().get(OtelMetricsCollector.STATUS))).findFirst().orElseThrow();
        assertThat(error.getAttributes().get(OtelMetricsCollector.ERROR_TYPE)).isEqualTo("503");
        assertThat(points).noneMatch(p -> String.valueOf(p.getAttributes().get(OtelMetricsCollector.ROUTE)).contains("wp-admin"));
    }

    @Test
    void logsEveryRequestJoinedToItsTrace() throws Exception {
        TracingMiddleware tracing = new TracingMiddleware(new OtelTracer(sdk));
        OtelRequestLogs access = new OtelRequestLogs(sdk);

        Context ok = ctx("/users/1", "/users/{id}");
        tracing.handle(ok, () -> access.handle(ok, () -> ok.res().status(200)));
        Context missing = ctx("/nope", null);
        tracing.handle(missing, () -> access.handle(missing, () -> missing.res().status(404)));
        Context boom = ctx("/users/2", "/users/{id}");
        assertThatThrownBy(() -> tracing.handle(boom, () -> access.handle(boom, () -> {
            throw new IllegalStateException("kaput");
        }))).isInstanceOf(IllegalStateException.class);

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertThat(records).hasSize(3);
        LogRecordData first = records.get(0);
        assertThat(first.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(first.getBodyValue().asString()).isEqualTo("GET /users/1 200");
        assertThat(first.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/users/{id}");
        assertThat(first.getAttributes().asMap().toString()).doesNotContain("secret");
        assertThat(first.getSpanContext().isValid()).isTrue();
        assertThat(first.getSpanContext().getTraceId()).isEqualTo(spans.getFinishedSpanItems().get(0).getTraceId());

        assertThat(records.get(1).getSeverity()).isEqualTo(Severity.WARN);
        assertThat(records.get(1).getAttributes().get(AttributeKey.stringKey("http.route")))
            .isEqualTo(MetricsMiddleware.UNMATCHED_ROUTE);
        assertThat(records.get(2).getSeverity()).isEqualTo(Severity.ERROR);
        assertThat(records.get(2).getAttributes().get(AttributeKey.stringKey("error.type")))
            .isEqualTo(IllegalStateException.class.getName());
        assertThat(spans.getFinishedSpanItems().get(0).getName()).isEqualTo("GET /users/{id}");
        assertThat(spans.getFinishedSpanItems().get(1).getName()).isEqualTo("GET");
    }

    @Test
    void discoverableAndUsableWithTheGlobalNoop() throws Exception {
        assertThat(ServiceLoader.load(com.ligero.spi.MetricsCollector.class).findFirst())
            .get().isInstanceOf(OtelMetricsCollector.class);
        new OtelMetricsCollector().record("GET", "/x", 200, 1_000);
        Context ctx = ctx("/x", "/x");
        new OtelRequestLogs().handle(ctx, () -> ctx.res().status(204));
    }
}
