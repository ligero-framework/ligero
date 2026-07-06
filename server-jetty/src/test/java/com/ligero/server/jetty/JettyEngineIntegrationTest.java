package com.ligero.server.jetty;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.http.NotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the same application code against the Jetty engine, proving the
 * ServerEngine SPI: no application changes, only a different runtime
 * dependency (DIP validated end-to-end).
 */
class JettyEngineIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Ligero app;

    private String start(Ligero app) throws IOException {
        this.app = app;
        app.engine(new JettyServerEngine());
        app.start();
        return "http://127.0.0.1:" + app.port();
    }

    private static Ligero newApp() {
        return Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
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
    void pathParamsAndJsonWorkOnJetty() throws Exception {
        Ligero app = newApp();
        app.get("/users/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParam("id"))));
        String base = start(app);

        HttpResponse<String> response = get(base + "/users/42");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"42\"");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void errorMappingWorksOnJetty() throws Exception {
        Ligero app = newApp();
        app.get("/missing", ctx -> { throw new NotFoundException("nope"); });
        app.get("/boom", ctx -> { throw new IllegalStateException("secret"); });
        String base = start(app);

        assertThat(get(base + "/missing").statusCode()).isEqualTo(404);
        HttpResponse<String> error = get(base + "/boom");
        assertThat(error.statusCode()).isEqualTo(500);
        assertThat(error.body()).doesNotContain("secret");
    }

    @Test
    void postBodyQueryParamsAndBodyLimitWorkOnJetty() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).maxBodyBytes(16).build());
        app.post("/echo", ctx -> ctx.text(ctx.bodyAsString() + "|" + ctx.queryParam("tag")));
        String base = start(app);

        HttpResponse<String> ok = client.send(
            HttpRequest.newBuilder(URI.create(base + "/echo?tag=t1"))
                .POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(ok.body()).isEqualTo("hello|t1");

        HttpResponse<String> tooBig = client.send(
            HttpRequest.newBuilder(URI.create(base + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(64))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(tooBig.statusCode()).isEqualTo(413);
    }

    @Test
    void redirectAndHeadersWorkOnJetty() throws Exception {
        Ligero app = newApp();
        app.get("/old", ctx -> ctx.redirect("/new", 308));
        app.get("/h", ctx -> ctx.text(String.valueOf(ctx.header("x-custom"))));
        String base = start(app);

        HttpResponse<String> redirect = get(base + "/old");
        assertThat(redirect.statusCode()).isEqualTo(308);
        assertThat(redirect.headers().firstValue("Location")).hasValue("/new");

        HttpResponse<String> headers = client.send(
            HttpRequest.newBuilder(URI.create(base + "/h")).header("X-CUSTOM", "v").build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(headers.body()).isEqualTo("v");
    }

    @Test
    void engineIsDiscoverableViaServiceLoader() {
        assertThat(java.util.ServiceLoader.load(com.ligero.spi.ServerEngine.class).stream()
                .map(p -> p.type().getName()))
            .contains(JettyServerEngine.class.getName());
    }
}
