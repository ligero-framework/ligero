package com.ligero.config.yaml;

import com.ligero.config.Config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: {@code Config.load()} discovers {@link YamlConfigSource} through
 * {@code ServiceLoader} and reads the real {@code ligero.yml} from the
 * classpath — no wiring needed by the app.
 */
class YamlServiceLoaderTest {

    @Test
    void configLoadDiscoversYamlOnTheClasspath() {
        Config config = Config.load();
        assertThat(config.get("app.name")).contains("ligero-yaml-test");
        assertThat(config.getInt("server.port", 0)).isEqualTo(8088);
    }
}
