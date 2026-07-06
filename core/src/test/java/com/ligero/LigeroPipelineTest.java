package com.ligero;

import com.ligero.config.LigeroConfig;
import com.ligero.http.Handler;
import com.ligero.http.HttpException;
import com.ligero.http.HttpHandler;
import com.ligero.middleware.Middleware;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the full core pipeline (routing, middleware, exception mapping)
 * against an injected in-memory engine — proving the ServerEngine SPI keeps
 * the core testable without any real server (DIP).
 */
class LigeroPipelineTest {

    /** Engine test double that just captures the composed root handler. */
    static final class FakeEngine implements ServerEngine {
        HttpHandler rootHandler;
        EngineConfig config;
        boolean stopped;

        @Override
        public void start(EngineConfig config, HttpHandler rootHandler) {
            this.config = config;
            this.rootHandler = rootHandler;
        }

        @Override
        public void stop(Duration grace) {
            stopped = true;
        }

        @Override
        public int port() {
            return 12345;
        }
    }

    private final FakeEngine engine = new FakeEngine();

    private Ligero appWith(java.util.function.Consumer<Ligero> routes) throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(engine);
        routes.accept(app);
        app.start();
        return app;
    }

    private FakeResponse exchange(String method, String uri) throws Exception {
        FakeResponse response = new FakeResponse();
        engine.rootHandler.handle(FakeRequest.of(method, uri), response);
        return response;
    }

    @Test
    void routesRequestsAndInjectsPathParams() throws Exception {
        appWith(app -> app.get("/users/{id}", ctx -> ctx.text("user " + ctx.pathParam("id"))));

        FakeResponse response = exchange("GET", "/users/42");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("user 42");
    }

    @Test
    void unmatchedRouteProduces404Json() throws Exception {
        appWith(app -> app.get("/known", ctx -> ctx.text("ok")));

        FakeResponse response = exchange("GET", "/unknown");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404");
    }

    @Test
    void wrongMethodProduces405WithAllow() throws Exception {
        appWith(app -> app.get("/a", ctx -> ctx.text("ok")).delete("/a", ctx -> ctx.text("ok")));

        FakeResponse response = exchange("POST", "/a");
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.headerValue("Allow")).isEqualTo("DELETE, GET");
    }

    @Test
    void optionsProduces204WithAllow() throws Exception {
        appWith(app -> app.get("/a", ctx -> ctx.text("ok")));

        FakeResponse response = exchange("OPTIONS", "/a");
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.headerValue("Allow")).isEqualTo("GET");
    }

    @Test
    void anyRegistersAllMethods() throws Exception {
        appWith(app -> app.any("/all", ctx -> ctx.text(ctx.method())));

        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            assertThat(exchange(method, "/all").body()).isEqualTo(method);
        }
    }

    @Test
    void middlewareWrapsHandlersInOrder() throws Exception {
        List<String> order = new ArrayList<>();
        Middleware outer = (ctx, chain) -> {
            order.add("outer");
            chain.proceed();
        };
        Middleware inner = (ctx, chain) -> {
            order.add("inner");
            chain.proceed();
        };
        appWith(app -> app.use(outer).use(inner).get("/x", ctx -> {
            order.add("handler");
            ctx.text("ok");
        }));

        exchange("GET", "/x");
        assertThat(order).containsExactly("outer", "inner", "handler");
    }

    @Test
    void pathScopedMiddlewareViaUse() throws Exception {
        List<String> hits = new ArrayList<>();
        appWith(app -> app
            .use("/api", (ctx, chain) -> {
                hits.add(ctx.path());
                chain.proceed();
            })
            .get("/api/x", ctx -> ctx.text("x"))
            .get("/y", ctx -> ctx.text("y")));

        exchange("GET", "/api/x");
        exchange("GET", "/y");
        assertThat(hits).containsExactly("/api/x");
    }

    @Test
    void httpExceptionsAreMappedToTheirStatus() throws Exception {
        appWith(app -> app.get("/teapot", ctx -> {
            throw new HttpException(418, "short and stout");
        }));

        FakeResponse response = exchange("GET", "/teapot");
        assertThat(response.getStatus()).isEqualTo(418);
        assertThat(response.body()).contains("short and stout");
    }

    @Test
    void unexpectedExceptionsBecomeOpaque500s() throws Exception {
        appWith(app -> app.get("/boom", ctx -> {
            throw new IllegalStateException("secret");
        }));

        FakeResponse response = exchange("GET", "/boom");
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("secret");
    }

    @Test
    void errorMessagesAreJsonEscaped() throws Exception {
        appWith(app -> app.get("/quote", ctx -> {
            throw new HttpException(400, "bad \"quoted\"\nvalue\\");
        }));

        FakeResponse response = exchange("GET", "/quote");
        assertThat(response.body()).contains("\\\"quoted\\\"").contains("\\n").contains("\\\\");
    }

    @Test
    void customExceptionHandlerMatchesSubclasses() throws Exception {
        appWith(app -> app
            .exception(RuntimeException.class, (e, ctx) -> ctx.status(599).text("custom: " + e.getMessage()))
            .get("/sub", ctx -> {
                throw new IllegalArgumentException("subtype");
            }));

        FakeResponse response = exchange("GET", "/sub");
        assertThat(response.getStatus()).isEqualTo(599);
        assertThat(response.body()).isEqualTo("custom: subtype");
    }

    @Test
    void failingExceptionHandlerFallsBackToPlain500() throws Exception {
        appWith(app -> app
            .exception(IllegalStateException.class, (e, ctx) -> {
                throw new RuntimeException("handler broke too");
            })
            .get("/double", ctx -> {
                throw new IllegalStateException("original");
            }));

        FakeResponse response = exchange("GET", "/double");
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("original").doesNotContain("handler broke too");
    }

    @Test
    void fallbackConfiguresCustom404() throws Exception {
        appWith(app -> app.fallback((Handler) ctx -> ctx.text("custom fallback")));

        FakeResponse response = exchange("GET", "/nowhere");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("custom fallback");
    }

    @Test
    void uncommittedResponsesAreEndedByThePipeline() throws Exception {
        appWith(app -> app.get("/silent", ctx -> { }));

        FakeResponse response = exchange("GET", "/silent");
        assertThat(response.isCommitted()).isTrue();
    }

    @Test
    void startTwiceFailsAndStopIsIdempotent() throws Exception {
        Ligero app = appWith(a -> a.get("/", ctx -> ctx.text("ok")));
        assertThatThrownBy(app::start).isInstanceOf(IllegalStateException.class);

        assertThat(app.port()).isEqualTo(12345);
        app.stop();
        assertThat(engine.stopped).isTrue();
        app.stop(); // no-op
        assertThatThrownBy(app::port).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void engineReceivesDerivedConfig() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(9999).gzip(true).build());
        app.engine(engine);
        app.get("/", ctx -> ctx.text("ok"));
        app.start();

        assertThat(engine.config.host()).isEqualTo("127.0.0.1");
        assertThat(engine.config.port()).isEqualTo(9999);
        assertThat(engine.config.gzip()).isTrue();
        app.stop();
    }

    @Test
    void requestsOutsideContextPathAre404() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).contextPath("/api").build());
        app.engine(engine);
        app.get("/users", ctx -> ctx.text("users"));
        app.start();

        assertThat(exchange("GET", "/api/users").body()).isEqualTo("users");
        assertThat(exchange("GET", "/users").getStatus()).isEqualTo(404);
        assertThat(exchange("GET", "/api/users?x=1").body()).isEqualTo("users");
        app.stop();
    }
}
