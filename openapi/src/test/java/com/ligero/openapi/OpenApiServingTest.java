package com.ligero.openapi;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The middleware side of OpenApi: a custom spec path, the opt-in Swagger UI
 * page, and pass-through for non-matching / non-GET requests.
 */
class OpenApiServingTest {

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesCustomSpecPathSwaggerUiAndPassesThrough() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.use(OpenApi.of(app, "Demo API", "1.0.0").at("/spec.json").withSwaggerUi("/docs"));
        app.get("/ping", ctx -> ctx.text("pong"));
        app.start();
        try {
            int port = app.port();

            // custom doc path
            HttpResponse<String> spec = get(port, "/spec.json");
            assertThat(spec.statusCode()).isEqualTo(200);
            assertThat(spec.body()).contains("\"openapi\":\"3.0.3\"").contains("\"/ping\"");

            // swagger UI html
            HttpResponse<String> ui = get(port, "/docs");
            assertThat(ui.statusCode()).isEqualTo(200);
            assertThat(ui.body())
                .contains("swagger-ui")
                .contains("Demo API")
                .contains("'/spec.json'"); // the page points at the doc path

            // a path the middleware doesn't own is passed down the chain
            HttpResponse<String> ping = get(port, "/ping");
            assertThat(ping.statusCode()).isEqualTo(200);
            assertThat(ping.body()).isEqualTo("pong");
        } finally {
            app.stop();
        }
    }

    @Test
    void nonGetRequestsArePassedThrough() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.use(OpenApi.of(app, "Demo", "1.0.0"));
        app.post("/openapi.json", ctx -> ctx.status(201).text("created"));
        app.start();
        try {
            HttpResponse<String> post = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/openapi.json"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            // POST is not intercepted by the (GET-only) middleware: the route runs
            assertThat(post.statusCode()).isEqualTo(201);
            assertThat(post.body()).isEqualTo("created");
        } finally {
            app.stop();
        }
    }
}
