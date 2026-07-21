package com.ligero;

import com.ligero.config.LigeroConfig;
import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleHooksTest {

    static final class FakeEngine implements ServerEngine {
        @Override public void start(EngineConfig config, HttpHandler rootHandler) { }
        @Override public void stop(Duration grace) { }
        @Override public int port() { return 12345; }
    }

    @Test
    void startAndStopHooksRunInOrderAndOneFailureDoesNotAbortTheRest() throws Exception {
        List<String> log = new ArrayList<>();
        Ligero app = Ligero.create(LigeroConfig.builder().environment(Map.of()).build());
        app.engine(new FakeEngine());

        app.onStart(() -> log.add("start-1"))
           .onStart(() -> { throw new RuntimeException("bad hook"); })
           .onStart(() -> log.add("start-2"))
           .onStop(() -> log.add("stop-1"));

        app.start();
        assertThat(log).containsExactly("start-1", "start-2"); // ran despite the failing hook

        app.stop();
        assertThat(log).containsExactly("start-1", "start-2", "stop-1");
    }
}
