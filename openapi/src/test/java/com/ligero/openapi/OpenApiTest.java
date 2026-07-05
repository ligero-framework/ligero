package com.ligero.openapi;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildsDocumentFromRoutes() {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.get("/users", ctx -> { });
        app.post("/users", ctx -> { });
        app.get("/users/{id}", ctx -> { });
        app.get("/files/*path", ctx -> { });

        Map<String, Object> doc = OpenApi.of(app, "Demo", "1.2.3").document();

        assertThat(doc.get("openapi")).isEqualTo("3.0.3");
        assertThat((Map<String, Object>) doc.get("info"))
            .containsEntry("title", "Demo").containsEntry("version", "1.2.3");
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys("/users", "/users/{id}").doesNotContainKey("/files/*path");
        Map<String, Object> users = (Map<String, Object>) paths.get("/users");
        assertThat(users).containsKeys("get", "post");
        Map<String, Object> byId = (Map<String, Object>) ((Map<String, Object>) paths.get("/users/{id}")).get("get");
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) byId.get("parameters");
        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0)).containsEntry("name", "id").containsEntry("in", "path");
    }

    @Test
    void servesDocumentOverHttp() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.use(OpenApi.of(app, "Demo API", "0.1.0"));
        app.get("/users/{id}", ctx -> ctx.text("u"));
        app.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"openapi\":\"3.0.3\"")
                .contains("\"/users/{id}\"")
                .contains("\"Demo API\"");
        } finally {
            app.stop();
        }
    }
}
