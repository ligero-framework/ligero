package com.ligero.server;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.http.NotFoundException;
import com.ligero.middleware.CorsMiddleware;
import com.ligero.middleware.RequestIdMiddleware;
import com.ligero.middleware.StaticFilesMiddleware;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests exercising the full stack: JDK engine + core pipeline +
 * Jackson BodyMapper discovered via ServiceLoader.
 */
class LigeroIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Ligero app;

    private String start(Ligero app) throws IOException {
        this.app = app;
        app.start();
        return "http://127.0.0.1:" + app.port();
    }

    private static Ligero newApp() {
        return Ligero.create(LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0).build());
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pathParamsWorkEndToEnd() throws Exception {
        // regression for B1: path params were silently dropped by the server
        Ligero app = newApp();
        app.get("/users/{id}/posts/{postId}", ctx ->
            ctx.json(Map.of("id", ctx.pathParam("id"), "post", ctx.pathParam("postId"))));
        String base = start(app);

        HttpResponse<String> response = get(base + "/users/42/posts/7");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"42\"").contains("\"post\":\"7\"");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void legacyBiConsumerHandlersStillWork() throws Exception {
        Ligero app = newApp();
        app.get("/hello/{name}", (req, res) ->
            res.json(Map.of("hello", req.getPathParams().get("name"))));
        String base = start(app);

        assertThat(get(base + "/hello/world").body()).contains("\"hello\":\"world\"");
    }

    @Test
    void queryParamsSupportMultipleValuesAndNoValue() throws Exception {
        Ligero app = newApp();
        app.get("/q", ctx -> ctx.json(Map.of(
            "tags", ctx.queryParams("tag"),
            "flag", String.valueOf(ctx.queryParam("flag")))));
        String base = start(app);

        HttpResponse<String> response = get(base + "/q?tag=a&tag=b&flag");
        assertThat(response.body()).contains("[\"a\",\"b\"]").contains("\"flag\":\"\"");
    }

    @Test
    void unknownRouteReturnsJson404() throws Exception {
        Ligero app = newApp();
        app.get("/known", ctx -> ctx.text("ok"));
        String base = start(app);

        HttpResponse<String> response = get(base + "/unknown");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404");
    }

    @Test
    void wrongMethodReturns405WithAllowHeader() throws Exception {
        Ligero app = newApp();
        app.get("/resource", ctx -> ctx.text("ok"));
        app.delete("/resource", ctx -> ctx.text("gone"));
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/resource"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValue("DELETE, GET");
    }

    @Test
    void optionsOnExistingPathListsAllowedMethods() throws Exception {
        Ligero app = newApp();
        app.get("/resource", ctx -> ctx.text("ok"));
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/resource"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Allow")).hasValue("GET");
    }

    @Test
    void postJsonBodyRoundTrip() throws Exception {
        record User(String name) { }
        Ligero app = newApp();
        app.post("/users", ctx -> {
            User user = ctx.body(User.class);
            ctx.status(201).json(Map.of("created", user.name()));
        });
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/users"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ada\"}")).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"created\":\"Ada\"");
    }

    @Test
    void httpExceptionsAreMappedWithoutLeakingStackTraces() throws Exception {
        Ligero app = newApp();
        app.get("/missing", ctx -> { throw new NotFoundException("nope"); });
        app.get("/boom", ctx -> { throw new IllegalStateException("secret detail"); });
        String base = start(app);

        HttpResponse<String> notFound = get(base + "/missing");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("nope");

        HttpResponse<String> error = get(base + "/boom");
        assertThat(error.statusCode()).isEqualTo(500);
        assertThat(error.body()).doesNotContain("secret detail").contains("Internal server error");
    }

    @Test
    void customExceptionHandlerTakesPrecedence() throws Exception {
        Ligero app = newApp();
        app.exception(IllegalArgumentException.class,
            (e, ctx) -> ctx.status(422).json(Map.of("reason", e.getMessage())));
        app.get("/custom", ctx -> { throw new IllegalArgumentException("bad input"); });
        String base = start(app);

        HttpResponse<String> response = get(base + "/custom");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("bad input");
    }

    @Test
    void customStatusHandlerRenders404() throws Exception {
        Ligero app = newApp();
        app.error(404, ctx -> ctx.html("<h1>lost</h1>"));
        String base = start(app);

        HttpResponse<String> response = get(base + "/nowhere");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("<h1>lost</h1>");
    }

    @Test
    void redirectSupportsCustomStatus() throws Exception {
        Ligero app = newApp();
        app.get("/old", ctx -> ctx.redirect("/new", 308));
        String base = start(app);

        HttpResponse<String> response = get(base + "/old");
        assertThat(response.statusCode()).isEqualTo(308);
        assertThat(response.headers().firstValue("Location")).hasValue("/new");
    }

    @Test
    void headersAreCaseInsensitive() throws Exception {
        Ligero app = newApp();
        app.get("/headers", ctx -> ctx.text(String.valueOf(ctx.header("x-custom-header"))));
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/headers"))
                .header("X-CUSTOM-HEADER", "value").build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).isEqualTo("value");
    }

    @Test
    void contextPathIsStrippedBeforeRouting() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).contextPath("/api").build());
        app.get("/users", ctx -> ctx.text("users"));
        String base = start(app);

        assertThat(get(base + "/api/users").body()).isEqualTo("users");
        assertThat(get(base + "/users").statusCode()).isEqualTo(404);
    }

    @Test
    void oversizedBodyIsRejectedWith413() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).maxBodyBytes(16).build());
        app.post("/upload", ctx -> ctx.text(ctx.bodyAsString()));
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/upload"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(64))).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void middlewareRunsAndRequestIdIsEchoed() throws Exception {
        Ligero app = newApp();
        app.use(new RequestIdMiddleware());
        app.get("/id", ctx -> ctx.text(String.valueOf((String) ctx.attribute(RequestIdMiddleware.ATTRIBUTE))));
        String base = start(app);

        HttpResponse<String> response = get(base + "/id");
        String echoed = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(response.body()).isEqualTo(echoed);
    }

    @Test
    void corsPreflightWorksEndToEnd() throws Exception {
        Ligero app = newApp();
        app.use(CorsMiddleware.builder().allowOrigins("https://a.example").build());
        app.post("/data", ctx -> ctx.text("ok"));
        String base = start(app);

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/data"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://a.example")
                .header("Access-Control-Request-Method", "POST")
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
            .hasValue("https://a.example");
    }

    @Test
    void staticFilesAreServedAndTraversalIsBlocked(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("app.js"), "console.log(1)");
        Files.writeString(dir.getParent().resolve("outside.txt"), "secret");

        Ligero app = newApp();
        app.use(StaticFilesMiddleware.external("/static", dir));
        String base = start(app);

        HttpResponse<String> ok = get(base + "/static/app.js");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("console.log");

        // encoded traversal: rejected up-front by the secure-defaults hygiene
        // middleware (400); with the baseline disabled it would fall through
        // to a 404 from the traversal-safe static handler
        HttpResponse<String> attack = get(base + "/static/%2e%2e/outside.txt");
        assertThat(attack.statusCode()).isEqualTo(400);
    }

    @Test
    void gzipCompressesLargeResponsesWhenEnabled() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).gzip(true).gzipMinBytes(10).build());
        String payload = "a".repeat(2048);
        app.get("/big", ctx -> ctx.text(payload));
        String base = start(app);

        HttpResponse<byte[]> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/big"))
                .header("Accept-Encoding", "gzip").build(),
            HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
            assertThat(new String(in.readAllBytes())).isEqualTo(payload);
        }
    }

    @Test
    void routeGroupsComposePrefixes() throws Exception {
        Ligero app = newApp();
        app.group("/api/v1", api -> {
            api.get("/users", ctx -> ctx.text("users"));
            api.group("/admin", admin -> admin.get("/stats", ctx -> ctx.text("stats")));
        });
        String base = start(app);

        assertThat(get(base + "/api/v1/users").body()).isEqualTo("users");
        assertThat(get(base + "/api/v1/admin/stats").body()).isEqualTo("stats");
    }

    @Test
    void concurrentRequestsAreServed() throws Exception {
        Ligero app = newApp();
        app.get("/slow", ctx -> {
            Thread.sleep(50);
            ctx.text("done");
        });
        String base = start(app);

        var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> get(base + "/slow")));
            }
            for (var future : futures) {
                assertThat(future.get().body()).isEqualTo("done");
            }
        }
    }

    @Test
    void stopIsGracefulAndIdempotent() throws Exception {
        Ligero app = newApp();
        app.get("/ping", ctx -> ctx.text("pong"));
        String base = start(app);
        assertThat(get(base + "/ping").statusCode()).isEqualTo(200);

        app.stop();
        app.stop(); // second call must be a no-op

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> get(base + "/ping"))).isNotNull();
        this.app = null;
    }
}
