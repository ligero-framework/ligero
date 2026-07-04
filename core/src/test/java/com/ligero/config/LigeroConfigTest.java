package com.ligero.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LigeroConfigTest {

    @Test
    void providesSensibleDefaults() {
        LigeroConfig config = LigeroConfig.builder().environment(Map.of()).build();
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.contextPath()).isEqualTo("/");
        assertThat(config.maxBodyBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(config.virtualThreads()).isTrue();
        assertThat(config.gzip()).isFalse();
        assertThat(config.shutdownGrace()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void environmentVariablesOverrideDefaults() {
        LigeroConfig config = LigeroConfig.builder()
            .environment(Map.of("LIGERO_PORT", "9090", "LIGERO_GZIP", "true"))
            .build();
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.gzip()).isTrue();
    }

    @Test
    void explicitBuilderValuesWinOverEnvironment() {
        LigeroConfig config = LigeroConfig.builder()
            .environment(Map.of("LIGERO_PORT", "9090"))
            .port(7070)
            .build();
        assertThat(config.port()).isEqualTo(7070);
    }

    @Test
    void normalizesContextPath() {
        LigeroConfig config = LigeroConfig.builder().environment(Map.of()).contextPath("api/").build();
        assertThat(config.contextPath()).isEqualTo("/api");
    }

    @Test
    void validatesRanges() {
        assertThatThrownBy(() -> LigeroConfig.builder().environment(Map.of()).port(70000).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LigeroConfig.builder().environment(Map.of()).maxBodyBytes(0).build())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
