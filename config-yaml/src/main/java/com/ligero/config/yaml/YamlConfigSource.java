package com.ligero.config.yaml;

import com.ligero.spi.ConfigSource;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ConfigSource} backed by {@code ligero.yml} on the classpath, overlaid
 * with {@code ligero-<profile>.yml} when a profile is active.
 *
 * <p>Precedence for the active profile: {@code LIGERO_PROFILE} env var, then
 * the {@code ligero.profile} system property. Nested YAML is flattened to
 * dotted keys ({@code server.port}, {@code db.url}); the profile overlay wins
 * key by key. String values may reference the environment with
 * {@code ${VAR}} or {@code ${VAR:-default}}.</p>
 *
 * <pre>{@code
 * # ligero.yml
 * server:
 *   port: 8080
 * db:
 *   url: ${DB_URL:-jdbc:postgresql://localhost:5432/app}
 * }</pre>
 */
public final class YamlConfigSource implements ConfigSource {

    private static final Pattern INTERPOLATION =
        Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(?::-([^}]*))?}");

    private final String profile;
    private final Map<String, String> values;

    /** Loads from the classpath using the ambient profile and environment. */
    public YamlConfigSource() {
        this(resolveProfile(System.getenv(), System.getProperties()::getProperty),
            YamlConfigSource::classpath, interpolatorFrom(System.getenv(), System.getProperties()::getProperty));
    }

    /** Explicit inputs for tests. */
    YamlConfigSource(String profile, UnaryOperator<String> resourceLoader, UnaryOperator<String> interpolator) {
        this.profile = profile;
        Map<String, String> merged = new LinkedHashMap<>();
        flatten("", load(resourceLoader.apply("ligero.yml"), resourceLoader.apply("ligero.yaml")), merged);
        if (profile != null && !profile.isBlank()) {
            flatten("", load(resourceLoader.apply("ligero-" + profile + ".yml"),
                resourceLoader.apply("ligero-" + profile + ".yaml")), merged);
        }
        merged.replaceAll((k, v) -> interpolator.apply(v));
        this.values = Map.copyOf(merged);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Optional<String> profile() {
        return Optional.ofNullable(profile).filter(p -> !p.isBlank());
    }

    @Override
    public int priority() {
        return 100; // above a plain properties source
    }

    // ------------------------------------------------------------- internals

    static String resolveProfile(Map<String, String> env, UnaryOperator<String> sysprops) {
        String fromEnv = env.get("LIGERO_PROFILE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = sysprops.apply("ligero.profile");
        return fromProp == null || fromProp.isBlank() ? null : fromProp.trim();
    }

    private static String classpath(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yml, String yaml) {
        String content = yml != null ? yml : yaml;
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Object root = new Yaml().load(content);
        return root instanceof Map ? (Map<String, Object>) root : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                flatten(key, (Map<String, Object>) map, out);
            } else if (value instanceof List<?> list) {
                out.put(key, list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
            } else if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
    }

    static UnaryOperator<String> interpolatorFrom(Map<String, String> env, UnaryOperator<String> sysprops) {
        return raw -> {
            if (raw == null || raw.indexOf("${") < 0) {
                return raw;
            }
            Matcher m = INTERPOLATION.matcher(raw);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                String fallback = m.group(2);
                String value = env.get(name);
                if (value == null) {
                    value = sysprops.apply(name);
                }
                if (value == null) {
                    value = fallback != null ? fallback : "";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            return sb.toString();
        };
    }
}
