package com.ligero;

import com.ligero.config.LigeroConfig;
import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP QUERY method (safe + idempotent, with a body) routes like any other
 * verb and its body is readable, at the app and route-group level.
 */
class QueryMethodTest {

    static final class FakeEngine implements ServerEngine {
        HttpHandler root;
        @Override public void start(EngineConfig config, HttpHandler root) { this.root = root; }
        @Override public void stop(Duration grace) { }
        @Override public int port() { return 0; }
    }

    private final FakeEngine engine = new FakeEngine();

    private FakeResponse exchange(Ligero app, String method, String uri, String body) throws Exception {
        FakeResponse response = new FakeResponse();
        engine.root.handle(FakeRequest.of(method, uri).body(body), response);
        return response;
    }

    @Test
    void queryRoutesAndReadsTheBody() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(engine);
        app.query("/search", ctx -> ctx.text("q=" + ctx.bodyAsString()));
        app.start();

        FakeResponse response = exchange(app, "QUERY", "/search", "{\"term\":\"ligero\"}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("q={\"term\":\"ligero\"}");
    }

    @Test
    void queryWorksInsideARouteGroup() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(engine);
        app.group("/api", api -> api.query("/find", ctx -> ctx.text("found")));
        app.start();

        assertThat(exchange(app, "QUERY", "/api/find", "body").body()).isEqualTo("found");
    }

    @Test
    void aQueryToAGetOnlyRouteIs405() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(engine);
        app.get("/only-get", ctx -> ctx.text("ok"));
        app.start();

        assertThat(exchange(app, "QUERY", "/only-get", "").getStatus()).isEqualTo(405);
    }
}
