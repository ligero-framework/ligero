package com.ligero.config.yaml;

import com.ligero.config.Config;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class YamlConfigSourceTest {

    private static final String BASE = """
        server:
          port: 8080
          gzip: true
        db:
          url: ${DB_URL:-jdbc:postgresql://localhost:5432/app}
          pool: 10
        cors:
          origins: [https://a.com, https://b.com]
        """;

    private static final String DEV = """
        server:
          port: 8081
        db:
          url: jdbc:h2:mem:app
        """;

    private static UnaryOperator<String> resources(String base, String dev) {
        return name -> switch (name) {
            case "ligero.yml" -> base;
            case "ligero-dev.yml" -> dev;
            default -> null;
        };
    }

    @Test
    void flattensNestedKeysAndLists() {
        YamlConfigSource yaml = new YamlConfigSource(null, resources(BASE, null), UnaryOperator.identity());
        assertThat(yaml.get("server.port")).contains("8080");
        assertThat(yaml.get("server.gzip")).contains("true");
        assertThat(yaml.get("db.pool")).contains("10");
        assertThat(yaml.get("cors.origins")).contains("https://a.com,https://b.com");
        assertThat(yaml.profile()).isEmpty();
    }

    @Test
    void profileOverlayWinsKeyByKey() {
        YamlConfigSource yaml = new YamlConfigSource("dev", resources(BASE, DEV), UnaryOperator.identity());
        assertThat(yaml.get("server.port")).contains("8081");   // overridden
        assertThat(yaml.get("db.url")).contains("jdbc:h2:mem:app"); // overridden
        assertThat(yaml.get("db.pool")).contains("10");         // inherited from base
        assertThat(yaml.profile()).contains("dev");
    }

    @Test
    void interpolatesEnvWithDefault() {
        UnaryOperator<String> withEnv = YamlConfigSource.interpolatorFrom(
            Map.of("DB_URL", "jdbc:postgresql://prod/db"), k -> null);
        YamlConfigSource yaml = new YamlConfigSource(null, resources(BASE, null), withEnv);
        assertThat(yaml.get("db.url")).contains("jdbc:postgresql://prod/db");

        UnaryOperator<String> noEnv = YamlConfigSource.interpolatorFrom(Map.of(), k -> null);
        YamlConfigSource fallback = new YamlConfigSource(null, resources(BASE, null), noEnv);
        assertThat(fallback.get("db.url")).contains("jdbc:postgresql://localhost:5432/app");
    }

    @Test
    void resolveProfilePrefersEnvThenSysprop() {
        assertThat(YamlConfigSource.resolveProfile(Map.of("LIGERO_PROFILE", "prod"), k -> "dev")).isEqualTo("prod");
        assertThat(YamlConfigSource.resolveProfile(Map.of(), k -> "dev".equals(k) ? null : "dev")).isEqualTo("dev");
        assertThat(YamlConfigSource.resolveProfile(Map.of(), k -> null)).isNull();
    }

    @Test
    void configFacadeReadsTypedValuesAndProfile() {
        YamlConfigSource yaml = new YamlConfigSource("dev", resources(BASE, DEV), UnaryOperator.identity());
        Config config = Config.of(List.of(yaml));
        assertThat(config.getInt("db.pool", 5)).isEqualTo(10);
        assertThat(config.getBoolean("server.gzip", false)).isTrue();
        assertThat(config.get("missing.key", "fallback")).isEqualTo("fallback");
        assertThat(config.profile()).contains("dev");
    }

    @Test
    void ligeroConfigReadsServerKeysFromYaml() {
        YamlConfigSource yaml = new YamlConfigSource("dev", resources(BASE, DEV), UnaryOperator.identity());
        LigeroConfig cfg = LigeroConfig.builder()
            .environment(Map.of())
            .config(Config.of(List.of(yaml)))
            .build();
        assertThat(cfg.port()).isEqualTo(8081);   // server.port from the dev overlay
        assertThat(cfg.gzip()).isTrue();          // server.gzip from base
    }

    @Test
    void explicitBuilderAndEnvStillWinOverYaml() {
        YamlConfigSource yaml = new YamlConfigSource(null, resources(BASE, null), UnaryOperator.identity());
        Config config = Config.of(List.of(yaml));
        // env beats yaml
        LigeroConfig fromEnv = LigeroConfig.builder()
            .environment(Map.of("LIGERO_PORT", "9090")).config(config).build();
        assertThat(fromEnv.port()).isEqualTo(9090);
        // explicit builder beats everything
        LigeroConfig explicit = LigeroConfig.builder()
            .environment(Map.of("LIGERO_PORT", "9090")).config(config).port(7000).build();
        assertThat(explicit.port()).isEqualTo(7000);
    }
}
