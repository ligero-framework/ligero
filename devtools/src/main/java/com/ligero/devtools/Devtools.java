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
 * production. As a safety net it only answers to loopback clients unless
 * {@link #allowRemote(boolean)} (or {@code LIGERO_DEVTOOLS_REMOTE=true}) says
 * otherwise. Setting the environment variable {@code LIGERO_DEVTOOLS=false}
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
    private boolean allowRemote = "true".equalsIgnoreCase(System.getenv("LIGERO_DEVTOOLS_REMOTE"));

    private Devtools() {
    }

    /**
     * By default the dashboard and its API answer only to requests coming
     * from the local machine (loopback); anyone else gets a 404. Pass
     * {@code true} — or set {@code LIGERO_DEVTOOLS_REMOTE=true} — to open it
     * to other hosts, e.g. when the app runs inside a container and you
     * browse from the host. Only do that on a network you trust: the
     * dashboard shows bean arguments and return values.
     */
    public Devtools allowRemote(boolean allowRemote) {
        this.allowRemote = allowRemote;
        return this;
    }

    /** True when {@code address} is a loopback IP literal ({@code 127.0.0.1}, {@code ::1}). */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String literal = address.startsWith("[") && address.endsWith("]")
            ? address.substring(1, address.length() - 1) : address;
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        // only IP literals are accepted, so no DNS lookup can happen here
        if (!literal.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(literal).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
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
        if (!allowRemote) {
            app.use(BASE_PATH, (ctx, chain) -> {
                if (!isLoopback(ctx.remoteAddress())) {
                    // look like any unknown route: do not advertise the dashboard
                    throw new com.ligero.http.NotFoundException(
                        "No route matches " + ctx.method() + " " + ctx.path());
                }
                chain.proceed();
            });
        }
        app.use(new TraceMiddleware(store));

        app.get(BASE_PATH, ctx -> ctx.html(dashboardHtml()));

        app.get(BASE_PATH + "/api/graph", ctx -> ctx.res()
            .contentType("application/json; charset=utf-8")
            .send(Json.graph(beans.graph(), recorder.stereotypes(), recorder.unspied())));

        app.get(BASE_PATH + "/api/routes", ctx -> ctx.res()
            .contentType("application/json; charset=utf-8")
            .send(Json.routes(app.routes())));

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
