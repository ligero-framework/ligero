package com.ligero;

import com.ligero.config.LigeroConfig;
import com.ligero.http.Context;
import com.ligero.http.Handler;
import com.ligero.http.HttpException;
import com.ligero.http.HttpHandler;
import com.ligero.http.HttpRequest;
import com.ligero.http.HttpResponse;
import com.ligero.http.MethodNotAllowedException;
import com.ligero.http.NotFoundException;
import com.ligero.middleware.Middleware;
import com.ligero.middleware.MiddlewarePipeline;
import com.ligero.router.Router;
import com.ligero.spi.BodyMapper;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;
import com.ligero.spi.TemplateEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Main entry point of the Ligero framework.
 *
 * <pre>{@code
 * Ligero app = Ligero.create(8080);
 * app.use(new RequestLoggingMiddleware());
 * app.get("/hello/{name}", ctx -> ctx.json(Map.of("hello", ctx.pathParam("name"))));
 * app.start();
 * }</pre>
 *
 * <p>The server engine and JSON mapper are resolved through their SPIs
 * ({@link ServerEngine}, {@link BodyMapper}) via {@link ServiceLoader}, or can
 * be injected explicitly with {@link #engine(ServerEngine)} and
 * {@link #bodyMapper(BodyMapper)} — no {@code new} of infrastructure inside
 * the core (DIP).</p>
 */
public final class Ligero implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Ligero.class);

    /** Context attribute holding the matched route pattern (e.g. {@code /users/{id}}). */
    public static final String MATCHED_ROUTE_ATTRIBUTE = "ligero.route";

    private static final List<String> ANY_METHODS =
        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final LigeroConfig config;
    private final Router router = new Router();
    private final List<Middleware> middlewares = new ArrayList<>();
    private final Map<Class<? extends Throwable>, ExceptionHandler<? extends Throwable>> exceptionHandlers =
        new LinkedHashMap<>();
    private final Map<Integer, Handler> statusHandlers = new HashMap<>();
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final Map<String, com.ligero.websocket.WsHandler> webSockets = new LinkedHashMap<>();
    private final List<Runnable> startHooks = new ArrayList<>();
    private final List<Runnable> stopHooks = new ArrayList<>();

    private ServerEngine engine;
    private BodyMapper bodyMapper;
    private TemplateEngine templateEngine;
    private volatile boolean started;

    private Ligero(LigeroConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // Factory methods
    // ------------------------------------------------------------------

    /** App with configuration from defaults, {@code ligero.properties} and env vars. */
    public static Ligero create() {
        return new Ligero(LigeroConfig.load());
    }

    public static Ligero create(int port) {
        return new Ligero(LigeroConfig.builder().port(port).build());
    }

    public static Ligero create(int port, String contextPath) {
        return new Ligero(LigeroConfig.builder().port(port).contextPath(contextPath).build());
    }

    public static Ligero create(LigeroConfig config) {
        return new Ligero(config);
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    public Ligero get(String path, Handler handler) {
        return route("GET", path, handler);
    }

    public Ligero post(String path, Handler handler) {
        return route("POST", path, handler);
    }

    public Ligero put(String path, Handler handler) {
        return route("PUT", path, handler);
    }

    public Ligero patch(String path, Handler handler) {
        return route("PATCH", path, handler);
    }

    public Ligero delete(String path, Handler handler) {
        return route("DELETE", path, handler);
    }

    public Ligero head(String path, Handler handler) {
        return route("HEAD", path, handler);
    }

    public Ligero options(String path, Handler handler) {
        return route("OPTIONS", path, handler);
    }

    /** Registers the handler for every standard HTTP method. */
    public Ligero any(String path, Handler handler) {
        ANY_METHODS.forEach(method -> route(method, path, handler));
        return this;
    }

    public Ligero route(String method, String path, Handler handler) {
        router.add(method, path, handler);
        log.debug("Route registered: {} {}", method.toUpperCase(), path);
        return this;
    }

    /**
     * Registers a WebSocket endpoint. Requires an engine with WebSocket
     * support ({@code ligero-server-jetty}); the JDK engine fails at
     * startup when WebSocket routes exist.
     */
    public Ligero websocket(String path, com.ligero.websocket.WsHandler handler) {
        webSockets.put(com.ligero.router.PathNormalizer.normalize(path), handler);
        return this;
    }

    /** Groups routes under a shared prefix. */
    public Ligero group(String prefix, Consumer<RouteGroup> routes) {
        routes.accept(new RouteGroup(this, prefix));
        return this;
    }

    // Legacy-style (request, response) overloads, kept for API compatibility.

    public Ligero get(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        return get(path, adapt(handler));
    }

    public Ligero post(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        return post(path, adapt(handler));
    }

    public Ligero put(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        return put(path, adapt(handler));
    }

    public Ligero delete(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        return delete(path, adapt(handler));
    }

    /** Custom handler for unmatched routes. Equivalent to {@code error(404, ...)}. */
    public Ligero fallback(Handler handler) {
        return error(404, handler);
    }

    public Ligero fallback(BiConsumer<HttpRequest, HttpResponse> handler) {
        return fallback(adapt(handler));
    }

    private static Handler adapt(BiConsumer<HttpRequest, HttpResponse> handler) {
        return ctx -> handler.accept(ctx.req(), ctx.res());
    }

    // ------------------------------------------------------------------
    // Middleware and error handling
    // ------------------------------------------------------------------

    /** Appends a middleware to the pipeline (runs in registration order). */
    public Ligero use(Middleware middleware) {
        middlewares.add(middleware);
        return this;
    }

    /** Appends a middleware that only applies to paths under {@code pathPrefix}. */
    public Ligero use(String pathPrefix, Middleware middleware) {
        middlewares.add(MiddlewarePipeline.scoped(pathPrefix, middleware));
        return this;
    }

    /** Maps an exception type (and its subclasses) to a custom handler. */
    public <T extends Throwable> Ligero exception(Class<T> type, ExceptionHandler<T> handler) {
        exceptionHandlers.put(type, handler);
        return this;
    }

    /** Custom rendering for framework-generated statuses (404, 405, ...). */
    public Ligero error(int status, Handler handler) {
        statusHandlers.put(status, handler);
        return this;
    }

    // ------------------------------------------------------------------
    // Infrastructure injection (SPI overrides, mainly for tests)
    // ------------------------------------------------------------------

    public Ligero engine(ServerEngine engine) {
        this.engine = engine;
        return this;
    }

    public Ligero bodyMapper(BodyMapper bodyMapper) {
        this.bodyMapper = bodyMapper;
        return this;
    }

    public Ligero templateEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        return this;
    }

    /**
     * Attaches a started {@link com.ligero.beans.Beans} container: every bean
     * becomes available to handlers via {@code ctx.get(type)}.
     */
    public Ligero beans(com.ligero.beans.Beans beans) {
        beans.types().forEach(type -> services.put(type, beans.get(type)));
        return this;
    }

    /**
     * Registers a service for handler access via {@code ctx.get(type)}.
     * Deliberately minimal DI: explicit registration, no reflection,
     * no classpath scanning.
     */
    public <T> Ligero register(Class<T> type, T implementation) {
        services.put(type, implementation);
        return this;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Runs {@code hook} right after the server has started (port is bound). */
    public Ligero onStart(Runnable hook) {
        startHooks.add(hook);
        return this;
    }

    /** Runs {@code hook} when the server stops, before the engine shuts down. */
    public Ligero onStop(Runnable hook) {
        stopHooks.add(hook);
        return this;
    }

    private void runHooks(List<Runnable> hooks, String phase) {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.error("A {} hook failed", phase, e);
            }
        }
    }

    /** Starts the server. */
    public void start() throws IOException {
        if (started) {
            throw new IllegalStateException("Server is already running");
        }
        long startNanos = System.nanoTime();
        Banner.print(System.out);
        if (engine == null) {
            engine = ServiceLoader.load(ServerEngine.class).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No ServerEngine found. Add ligero-server-jdk (or another engine) to the classpath."));
        }
        if (bodyMapper == null) {
            bodyMapper = ServiceLoader.load(BodyMapper.class).findFirst().orElse(null);
        }
        if (templateEngine == null) {
            templateEngine = ServiceLoader.load(TemplateEngine.class).findFirst().orElse(null);
        }

        EngineConfig engineConfig = new EngineConfig(
            config.host(), config.port(), config.maxBodyBytes(), config.virtualThreads(),
            config.gzip(), config.gzipMinBytes(), bodyMapper, webSockets);
        engine.start(engineConfig, buildRootHandler());
        started = true;

        log.info(Banner.startedLine(config.host(), engine.port(), config.contextPath(),
            engine, config.virtualThreads(), startNanos));
        if (log.isDebugEnabled()) {
            router.routes().forEach((method, paths) ->
                paths.forEach(path -> log.debug("  {} {}", method, path)));
        }
        runHooks(startHooks, "start");
    }

    /** Stops the server gracefully within the configured shutdown window. */
    public void stop() {
        if (started) {
            runHooks(stopHooks, "stop");
            engine.stop(config.shutdownGrace());
            started = false;
            log.info("Ligero stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Actual bound port (resolves ephemeral port 0 configurations). */
    public int port() {
        if (!started) {
            throw new IllegalStateException("Server is not running");
        }
        return engine.port();
    }

    public String getContextPath() {
        return config.contextPath();
    }

    public LigeroConfig config() {
        return config;
    }

    /** Registered routes per method (diagnostics, OpenAPI generation). */
    public Map<String, List<String>> routes() {
        return router.routes();
    }

    // ------------------------------------------------------------------
    // Pipeline assembly
    // ------------------------------------------------------------------

    private HttpHandler buildRootHandler() {
        List<Middleware> pipeline = new ArrayList<>();
        if (config.secureDefaults()) {
            // OWASP-aligned baseline, on unless secureDefaults(false):
            // path hygiene first, then security headers on every response
            pipeline.add(new com.ligero.middleware.RequestHygieneMiddleware());
            pipeline.add(com.ligero.middleware.SecurityHeadersMiddleware.defaults());
        }
        pipeline.addAll(middlewares);
        Handler chain = MiddlewarePipeline.compose(pipeline, this::dispatch);
        return (request, response) -> {
            Context ctx = new Context(request, response, config.contextPath(), bodyMapper,
                templateEngine, services);
            try {
                chain.handle(ctx);
            } catch (Throwable t) {
                handleException(t, ctx);
            } finally {
                if (!response.isCommitted()) {
                    response.end();
                }
            }
        };
    }

    /** Terminal pipeline step: route matching and 404/405 semantics. */
    private void dispatch(Context ctx) throws Exception {
        String method = ctx.method();
        String path = ctx.path();

        if (!isWithinContextPath(ctx)) {
            throw new NotFoundException("No route matches " + method + " " + path);
        }

        Router.RouteMatch match = router.match(method, path);
        if (match != null) {
            ctx.pathParams().putAll(match.params());
            ctx.attribute(MATCHED_ROUTE_ATTRIBUTE, match.routePath());
            match.handler().handle(ctx);
            return;
        }

        Set<String> allowed = router.allowedMethods(path);
        if (!allowed.isEmpty()) {
            if ("OPTIONS".equals(method)) {
                ctx.header("Allow", String.join(", ", allowed)).status(204).res().end();
                return;
            }
            throw new MethodNotAllowedException(method, allowed);
        }
        throw new NotFoundException("No route matches " + method + " " + path);
    }

    /** Requests outside the configured context path never reach the routes. */
    private boolean isWithinContextPath(Context ctx) {
        String contextPath = config.contextPath();
        if ("/".equals(contextPath)) {
            return true;
        }
        String rawPath = ctx.req().getUri();
        int query = rawPath.indexOf('?');
        if (query >= 0) {
            rawPath = rawPath.substring(0, query);
        }
        rawPath = com.ligero.router.PathNormalizer.normalize(rawPath);
        return rawPath.equals(contextPath) || rawPath.startsWith(contextPath + "/");
    }

    private void handleException(Throwable t, Context ctx) {
        if (ctx.res().isCommitted()) {
            log.warn("Exception after response was committed for {} {}", ctx.method(), ctx.path(), t);
            return;
        }
        try {
            ExceptionHandler<Throwable> custom = findExceptionHandler(t.getClass());
            if (custom != null) {
                custom.handle(t, ctx);
                return;
            }
            if (t instanceof HttpException http) {
                if (http instanceof MethodNotAllowedException notAllowed) {
                    ctx.header("Allow", String.join(", ", notAllowed.getAllowedMethods()));
                }
                Handler statusHandler = statusHandlers.get(http.getStatus());
                if (statusHandler != null) {
                    ctx.status(http.getStatus());
                    statusHandler.handle(ctx);
                } else {
                    sendErrorJson(ctx, http.getStatus(), http.getMessage());
                }
                return;
            }
            // Unexpected exception: full details to the log, none to the client.
            log.error("Unhandled exception processing {} {}", ctx.method(), ctx.path(), t);
            sendErrorJson(ctx, 500, "Internal server error");
        } catch (Throwable secondary) {
            log.error("Exception handler itself failed", secondary);
            if (!ctx.res().isCommitted()) {
                ctx.res().status(500).contentType("text/plain; charset=utf-8").send("Internal server error");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ExceptionHandler<Throwable> findExceptionHandler(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            ExceptionHandler<? extends Throwable> handler = exceptionHandlers.get(current);
            if (handler != null) {
                return (ExceptionHandler<Throwable>) handler;
            }
        }
        return null;
    }

    private void sendErrorJson(Context ctx, int status, String message) {
        ctx.res().status(status).contentType("application/json")
           .send("{\"status\":" + status + ",\"error\":\"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
