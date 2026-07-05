package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HealthMiddlewareTest {

    private static Context context(String method, String uri) {
        return new Context(FakeRequest.of(method, uri), new FakeResponse(), "/", null, null);
    }

    @Test
    void respondsUpOnHealthPath() throws Exception {
        Context ctx = context("GET", "/health");
        HealthMiddleware.defaults().handle(ctx, () -> { throw new AssertionError("no chain"); });

        FakeResponse response = (FakeResponse) ctx.res();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void failingCheckYields503WithDetail() throws Exception {
        HealthMiddleware health = HealthMiddleware.builder()
            .check("db", () -> true)
            .check("cache", () -> { throw new IllegalStateException("down"); })
            .build();
        Context ctx = context("GET", "/health");
        health.handle(ctx, () -> { });

        FakeResponse response = (FakeResponse) ctx.res();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.body()).contains("\"db\":\"UP\"").contains("\"cache\":\"DOWN\"");
    }

    @Test
    void otherPathsPassThrough() throws Exception {
        AtomicBoolean proceeded = new AtomicBoolean();
        HealthMiddleware.defaults().handle(context("GET", "/users"), () -> proceeded.set(true));
        HealthMiddleware.defaults().handle(context("POST", "/health"), () -> proceeded.set(true));
        assertThat(proceeded).isTrue();
    }
}
