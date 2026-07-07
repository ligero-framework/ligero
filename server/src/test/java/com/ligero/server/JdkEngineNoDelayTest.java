package com.ligero.server;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the ~40 ms Nagle latency: starting the JDK engine must
 * leave {@code sun.net.httpserver.nodelay} enabled (TCP_NODELAY on) so the
 * built-in server doesn't stall keep-alive responses.
 */
class JdkEngineNoDelayTest {

    @Test
    void startingTheEngineEnablesTcpNoDelay() throws Exception {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.engine(new JdkServerEngine());
        app.get("/", ctx -> ctx.text("ok"));
        try {
            app.start();
            assertThat(System.getProperty("sun.net.httpserver.nodelay")).isEqualTo("true");
        } finally {
            app.stop();
        }
    }
}
