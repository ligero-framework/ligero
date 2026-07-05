package com.ligero.test;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * End-to-end test harness: starts the app on an ephemeral port, sends real
 * HTTP requests and returns simple assertable responses.
 *
 * <pre>{@code
 * try (LigeroTest test = LigeroTest.create(app -> {
 *     app.get("/users/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParam("id"))));
 * })) {
 *     LigeroTest.TestResponse response = test.get("/users/7").execute();
 *     assertEquals(200, response.status());
 * }
 * }</pre>
 */
public final class LigeroTest implements AutoCloseable {

    private final Ligero app;
    private final HttpClient client = HttpClient.newHttpClient();

    private LigeroTest(Ligero app) {
        this.app = app;
    }

    /** Builds an app on 127.0.0.1 with an ephemeral port, applies the setup and starts it. */
    public static LigeroTest create(Consumer<Ligero> setup) {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        setup.accept(app);
        return start(app);
    }

    /** Starts a pre-configured app (use port 0 to avoid collisions). */
    public static LigeroTest start(Ligero app) {
        try {
            app.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start test server", e);
        }
        return new LigeroTest(app);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + app.port();
    }

    public Ligero app() {
        return app;
    }

    public TestRequest get(String path) {
        return request("GET", path);
    }

    public TestRequest post(String path) {
        return request("POST", path);
    }

    public TestRequest put(String path) {
        return request("PUT", path);
    }

    public TestRequest patch(String path) {
        return request("PATCH", path);
    }

    public TestRequest delete(String path) {
        return request("DELETE", path);
    }

    public TestRequest request(String method, String path) {
        return new TestRequest(method, path);
    }

    @Override
    public void close() {
        app.stop();
    }

    /** Fluent request builder. */
    public final class TestRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;

        private TestRequest(String method, String path) {
            this.method = method;
            this.path = path;
        }

        public TestRequest header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public TestRequest body(String body) {
            return this.contentType("text/plain; charset=utf-8", body);
        }

        public TestRequest json(String json) {
            return contentType("application/json", json);
        }

        private TestRequest contentType(String contentType, String body) {
            headers.putIfAbsent("Content-Type", contentType);
            this.body = body;
            return this;
        }

        public TestResponse execute() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(builder::header);
            try {
                HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new TestResponse(response.statusCode(), response.headers().map(), response.body());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during test request", e);
            }
        }
    }

    /** Immutable response snapshot. */
    public record TestResponse(int status, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .findFirst().orElse(null);
        }
    }
}
