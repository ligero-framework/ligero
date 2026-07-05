package com.ligero.test;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LigeroTestTest {

    @Test
    void driveAppEndToEnd() {
        try (LigeroTest test = LigeroTest.create(app -> {
            app.get("/users/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParam("id"))));
            app.post("/echo", ctx -> ctx.status(201).text(ctx.bodyAsString()));
        })) {
            LigeroTest.TestResponse get = test.get("/users/9").execute();
            assertThat(get.status()).isEqualTo(200);
            assertThat(get.body()).contains("\"id\":\"9\"");
            assertThat(get.header("content-type")).isEqualTo("application/json");

            LigeroTest.TestResponse post = test.post("/echo").json("{\"x\":1}").execute();
            assertThat(post.status()).isEqualTo(201);
            assertThat(post.body()).isEqualTo("{\"x\":1}");

            LigeroTest.TestResponse missing = test.get("/nope").execute();
            assertThat(missing.status()).isEqualTo(404);
        }
    }

    @Test
    void headersAreSent() {
        try (LigeroTest test = LigeroTest.create(app ->
                app.get("/h", ctx -> ctx.text(String.valueOf(ctx.header("X-Custom")))))) {
            assertThat(test.get("/h").header("X-Custom", "v1").execute().body()).isEqualTo("v1");
        }
    }
}
