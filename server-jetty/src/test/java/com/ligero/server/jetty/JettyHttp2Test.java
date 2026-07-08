package com.ligero.server.jetty;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jetty engine speaks HTTP/2 cleartext (h2c) on the same port as HTTP/1.1:
 * an HTTP/2-capable client negotiates HTTP/2, an HTTP/1.1 client is unaffected.
 */
class JettyHttp2Test {

    private Ligero app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private String start() throws Exception {
        app = Ligero.create(LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0).build());
        app.engine(new JettyServerEngine());
        app.get("/ping", ctx -> ctx.text("pong"));
        app.start();
        return "http://127.0.0.1:" + app.port() + "/ping";
    }

    @Test
    void servesOverHttp2WhenTheClientAsksForIt() throws Exception {
        String url = start();
        try (HttpClient http2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
            HttpResponse<String> response =
                http2.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("pong");
        }
    }

    @Test
    void stillServesHttp11Clients() throws Exception {
        String url = start();
        try (HttpClient http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<String> response =
                http1.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(response.body()).isEqualTo("pong");
        }
    }
}
