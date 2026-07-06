package com.ligero.devtools;

import com.ligero.Ligero;
import com.ligero.beans.BeanDecorator;
import com.ligero.beans.Beans;
import com.ligero.http.SseEmitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Development dashboard for Ligero: a visual debugger served at
 * {@code /ligero/dev} showing the bean dependency graph (colored by
 * stereotype) and a live trace of every request through the layers —
 * controller, service, repository — with arguments, return values and
 * timings per call.
 *
 * <pre>{@code
 * Devtools devtools = Devtools.create();
 *
 * Beans beans = Beans.builder()
 *     .bind(ProductRepository.class, b -> new JdbcProductRepository(b.get(DataSource.class)))
 *     .bind(ProductService.class,    b -> new ProductService(b.get(ProductRepository.class)))
 *     .instrument(devtools.recorder())   // spies interface-typed beans
 *     .start();
 *
 * Ligero app = Ligero.create();
 * app.beans(beans);
 * devtools.install(app, beans);          // mounts /ligero/dev before app.start()
 * }</pre>
 *
 * <p><strong>Development profile only.</strong> The dashboard exposes
 * arguments and return values of your beans; never install it in
 * production. Setting the environment variable {@code LIGERO_DEVTOOLS=false}
 * turns {@link #install(Ligero, Beans)} into a no-op, so a deploy can switch
 * it off without a code change.</p>
 */
public final class Devtools {

    static final String BASE_PATH = "/ligero/dev";

    private static final Logger log = LoggerFactory.getLogger(Devtools.class);
    private static final int HISTORY = 100;
    private static final long KEEP_ALIVE_SECONDS = 15;

    private final TraceStore store = new TraceStore(HISTORY);
    private final DevtoolsRecorder recorder = new DevtoolsRecorder();

    private Devtools() {
    }

    public static Devtools create() {
        return new Devtools();
    }

    /**
     * The {@link BeanDecorator} to pass to {@code Beans.builder().instrument(...)}.
     * Interface-typed beans get a spy proxy; concrete-class beans pass through
     * untouched (and are listed on the dashboard as not traceable).
     */
    public BeanDecorator recorder() {
        return recorder;
    }

    /**
     * Mounts the dashboard and its API on the app. Call before
     * {@code app.start()}.
     */
    public Devtools install(Ligero app, Beans beans) {
        if ("false".equalsIgnoreCase(System.getenv("LIGERO_DEVTOOLS"))) {
            log.info("LIGERO_DEVTOOLS=false — devtools disabled");
            return this;
        }
        app.use(new TraceMiddleware(store));

        app.get(BASE_PATH, ctx -> ctx.html(dashboardHtml()));

        app.get(BASE_PATH + "/api/graph", ctx -> ctx.res()
            .contentType("application/json; charset=utf-8")
            .send(Json.graph(beans.graph(), recorder.stereotypes(), recorder.unspied())));

        app.get(BASE_PATH + "/api/requests", ctx -> ctx.res()
            .contentType("application/json; charset=utf-8")
            .send(Json.traces(store.recent())));

        app.get(BASE_PATH + "/api/stream", ctx -> {
            SseEmitter sse = ctx.sse();
            BlockingQueue<RequestTrace> queue = new LinkedBlockingQueue<>();
            Consumer<RequestTrace> subscriber = queue::add;
            store.subscribe(subscriber);
            try {
                while (true) {
                    RequestTrace trace = queue.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
                    if (trace == null) {
                        sse.comment("keep-alive");
                    } else {
                        sse.send("trace", Json.trace(trace));
                    }
                }
            } catch (UncheckedIOException clientGone) {
                // browser tab closed — normal termination of the stream
            } finally {
                store.unsubscribe(subscriber);
            }
        });

        log.info("Ligero devtools mounted at {} (development only)", BASE_PATH);
        return this;
    }

    private static String dashboardHtml() {
        try (InputStream in = Devtools.class.getResourceAsStream("dashboard.html")) {
            if (in == null) {
                throw new IllegalStateException("dashboard.html missing from ligero-devtools jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
