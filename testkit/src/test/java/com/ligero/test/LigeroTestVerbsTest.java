package com.ligero.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the remaining fluent helpers: the PUT/PATCH/DELETE verbs, a plain-text
 * body, and the {@code app()} accessor.
 */
class LigeroTestVerbsTest {

    @Test
    void exercisesEveryVerbBodyAndAppAccessor() {
        try (LigeroTest test = LigeroTest.create(app -> {
            app.put("/r", ctx -> ctx.text("put"));
            app.patch("/r", ctx -> ctx.text("patch"));
            app.delete("/r", ctx -> ctx.text("delete"));
            app.post("/echo", ctx -> ctx.text(ctx.bodyAsString()));
        })) {
            assertThat(test.put("/r").execute().body()).isEqualTo("put");
            assertThat(test.patch("/r").execute().body()).isEqualTo("patch");
            assertThat(test.delete("/r").execute().body()).isEqualTo("delete");

            // plain-text body() helper (defaults the content type)
            LigeroTest.TestResponse echoed = test.post("/echo").body("hello").execute();
            assertThat(echoed.body()).isEqualTo("hello");

            assertThat(test.app()).isSameAs(test.app());
            assertThat(test.baseUrl()).startsWith("http://127.0.0.1:");
        }
    }
}
