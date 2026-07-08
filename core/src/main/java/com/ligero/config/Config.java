package com.ligero.config;

import com.ligero.spi.ConfigSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Typed access to application configuration, merged from every
 * {@link ConfigSource} on the classpath (highest {@link ConfigSource#priority()}
 * wins). This is the app-facing companion to {@link LigeroConfig} (which holds
 * the framework's own settings): read your own keys here.
 *
 * <pre>{@code
 * Config config = Config.load();
 * String url = config.get("db.url").orElse("jdbc:h2:mem:app");
 * int pool   = config.getInt("db.pool", 10);
 * config.profile().ifPresent(p -> log.info("profile: {}", p));
 * }</pre>
 *
 * <p>With no config source present (e.g. a pure microservice without
 * {@code ligero-config-yaml}) every lookup is empty and the defaults apply —
 * config stays entirely optional.</p>
 */
public final class Config {

    private final List<ConfigSource> sources;

    private Config(List<ConfigSource> sources) {
        this.sources = sources;
    }

    /** Loads and merges the {@link ConfigSource}s discovered on the classpath. */
    public static Config load() {
        List<ConfigSource> sources = new ArrayList<>();
        ServiceLoader.load(ConfigSource.class).forEach(sources::add);
        return of(sources);
    }

    /** Builds a config over the given sources (useful for tests). */
    public static Config of(List<ConfigSource> sources) {
        List<ConfigSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(ConfigSource::priority).reversed());
        return new Config(ordered);
    }

    public Optional<String> get(String key) {
        for (ConfigSource source : sources) {
            Optional<String> value = source.get(key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(Integer::parseInt).orElse(defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return get(key).map(Long::parseLong).orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /** The active profile, if a source resolved one (e.g. {@code "dev"}). */
    public Optional<String> profile() {
        for (ConfigSource source : sources) {
            Optional<String> profile = source.profile();
            if (profile.isPresent()) {
                return profile;
            }
        }
        return Optional.empty();
    }
}
