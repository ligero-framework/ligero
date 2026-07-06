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

/** The OWASP baseline is on by default and can be disabled explicitly. */
class SecureDefaultsTest {

    static final class CapturingEngine implements ServerEngine {
        HttpHandler root;

        public void start(EngineConfig cfg, HttpHandler root) {
            this.root = root;
        }

        public void stop(Duration grace) {
        }

        public int port() {
            return 0;
        }
    }

    private FakeResponse run(boolean secureDefaults, String uri) throws Exception {
        CapturingEngine engine = new CapturingEngine();
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).secureDefaults(secureDefaults).build());
        app.engine(engine);
        app.get("/ok", ctx -> ctx.text("fine"));
        app.start();
        FakeResponse response = new FakeResponse();
        engine.root.handle(FakeRequest.of("GET", uri), response);
        return response;
    }

    @Test
    void securityHeadersAndHygieneAreOnByDefault() throws Exception {
        FakeResponse ok = run(true, "/ok");
        assertThat(ok.headerValue("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(ok.headerValue("X-Frame-Options")).isEqualTo("DENY");

        FakeResponse attack = run(true, "/a%00b");
        assertThat(attack.getStatus()).isEqualTo(400);
    }

    @Test
    void baselineCanBeDisabled() throws Exception {
        FakeResponse ok = run(false, "/ok");
        assertThat(ok.headerValue("X-Content-Type-Options")).isNull();
    }

    @Test
    void configFlagDefaultsTrueAndReadsEnv() {
        assertThat(LigeroConfig.builder().environment(Map.of()).build().secureDefaults()).isTrue();
        assertThat(LigeroConfig.builder().environment(Map.of("LIGERO_SECURE_DEFAULTS", "false"))
            .build().secureDefaults()).isFalse();
    }
}
