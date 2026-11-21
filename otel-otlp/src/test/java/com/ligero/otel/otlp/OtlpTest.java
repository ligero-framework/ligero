package com.ligero.otel.otlp;

import com.ligero.Ligero;
import com.ligero.test.LigeroTest;

import io.opentelemetry.sdk.OpenTelemetrySdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A fake OTLP/HTTP collector (a Ligero app) receives what an instrumented app exports. */
class OtlpTest {

    private final Map<String, List<String>> received = new ConcurrentHashMap<>();
    private LigeroTest collector;
    private LigeroTest app;

    private LigeroTest collector() {
        return LigeroTest.create(c -> {
            for (String signal : new String[] {"traces", "metrics", "logs"}) {
                c.post("/v1/" + signal, ctx -> {
                    received.computeIfAbsent(signal, s -> new CopyOnWriteArrayList<>())
                        .add(ctx.header("Content-Type") + " " + ctx.bodyAsBytes().length + " " + ctx.header("x-api-key"));
                    ctx.status(200).res().contentType("application/x-protobuf").send("");
                });
            }
        });
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.close();
        }
        if (collector != null) {
            collector.close();
        }
    }

    @Test
    void exportsTracesMetricsAndLogsOverHttpProtobuf() throws Exception {
        collector = collector();
        OpenTelemetrySdk[] sdk = new OpenTelemetrySdk[1];
        app = LigeroTest.create(a -> {
            sdk[0] = Otlp.builder()
                .serviceName("shop")
                .endpoint(collector.baseUrl())
                .property("otel.exporter.otlp.headers", "x-api-key=k3y")
                .property("otel.metric.export.interval", "200")
                .property("otel.bsp.schedule.delay", "50")
                .property("otel.blrp.schedule.delay", "50")
                .global(false)
                .instrument(a);
            a.get("/users/{id}", ctx -> ctx.text("user " + ctx.pathParam("id")));
        });
        assertThat(app.get("/users/7").execute().body()).isEqualTo("user 7");
        assertThat(app.get("/missing").execute().status()).isEqualTo(404);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && received.size() < 3) {
            Thread.sleep(50);
        }
        assertThat(received).containsOnlyKeys("traces", "metrics", "logs");
        assertThat(received.get("traces").get(0)).startsWith("application/x-protobuf").endsWith(" k3y");
        assertThat(sdk[0].getSdkTracerProvider().toString()).contains("service.name=\"shop\"");
    }

    @Test
    void stoppingTheAppFlushesWhatIsBuffered() {
        collector = collector();
        Ligero ligero = Ligero.create(0);
        Otlp.builder().endpoint(collector.baseUrl()).global(false)
            .property("otel.bsp.schedule.delay", "60000")   // nothing leaves on its own
            .property("otel.metric.export.interval", "60000")
            .instrument(ligero);
        ligero.get("/x", ctx -> ctx.text("x"));
        app = LigeroTest.start(ligero);
        app.get("/x").execute();
        assertThat(received).doesNotContainKey("traces");
        app.close();
        app = null;
        assertThat(received).containsKeys("traces", "metrics", "logs");
    }

    @Test
    void anEnvironmentProtocolSettingWinsOverTheDefault() {
        OpenTelemetrySdk sdk = Otlp.builder().global(false)
            .property("otel.traces.exporter", "none")
            .property("otel.metrics.exporter", "none")
            .property("otel.logs.exporter", "none")
            .build().sdk();
        assertThat(sdk).isNotNull();
        sdk.close();
    }
}
