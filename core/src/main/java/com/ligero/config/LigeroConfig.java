package com.ligero.config;

import com.ligero.router.PathNormalizer;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Immutable, typed application configuration (Java record — architecture
 * goal of type-safe config).
 *
 * <p>Values are resolved with the following precedence (highest wins):
 * explicit builder calls &gt; environment variables ({@code LIGERO_*}) &gt;
 * classpath {@code ligero.properties} ({@code ligero.*} keys) &gt; defaults.</p>
 *
 * @param host           bind address (default {@code 0.0.0.0})
 * @param port           bind port; 0 requests an ephemeral port (default 8080)
 * @param contextPath    base path for all routes (default {@code /})
 * @param maxBodyBytes   request body limit in bytes (default 10 MiB)
 * @param virtualThreads serve each request on a virtual thread (default true)
 * @param gzip           gzip responses when the client accepts it (default false)
 * @param gzipMinBytes   minimum response size to compress (default 1024)
 * @param shutdownGrace  graceful shutdown window (default 10 s)
 * @param secureDefaults apply the OWASP-aligned security baseline
 *                       automatically: security headers on every response and
 *                       request-path hygiene checks (default true; disable
 *                       explicitly if you provide your own)
 */
public record LigeroConfig(
    String host,
    int port,
    String contextPath,
    long maxBodyBytes,
    boolean virtualThreads,
    boolean gzip,
    int gzipMinBytes,
    Duration shutdownGrace,
    boolean secureDefaults) {

    public static final String PROPERTIES_RESOURCE = "ligero.properties";

    public LigeroConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        contextPath = PathNormalizer.normalizeContextPath(contextPath);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Defaults overlaid with classpath properties and environment variables. */
    public static LigeroConfig load() {
        return builder().build();
    }

    public static final class Builder {
        private String host;
        private Integer port;
        private String contextPath;
        private Long maxBodyBytes;
        private Boolean virtualThreads;
        private Boolean gzip;
        private Integer gzipMinBytes;
        private Duration shutdownGrace;
        private Boolean secureDefaults;
        private Map<String, String> env = System.getenv();
        private Properties classpathProperties;
        private Config config;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath;
            return this;
        }

        public Builder maxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        public Builder virtualThreads(boolean virtualThreads) {
            this.virtualThreads = virtualThreads;
            return this;
        }

        public Builder gzip(boolean gzip) {
            this.gzip = gzip;
            return this;
        }

        public Builder gzipMinBytes(int gzipMinBytes) {
            this.gzipMinBytes = gzipMinBytes;
            return this;
        }

        public Builder shutdownGrace(Duration shutdownGrace) {
            this.shutdownGrace = shutdownGrace;
            return this;
        }

        /** Disables (or re-enables) the automatic security baseline. */
        public Builder secureDefaults(boolean secureDefaults) {
            this.secureDefaults = secureDefaults;
            return this;
        }

        /** Overrides the environment source — intended for tests. */
        public Builder environment(Map<String, String> env) {
            this.env = env;
            return this;
        }

        /** Overrides the config sources — intended for tests. */
        public Builder config(Config config) {
            this.config = config;
            return this;
        }

        public LigeroConfig build() {
            Properties props = classpathProperties();
            Config cfg = config != null ? config : Config.load();
            return new LigeroConfig(
                resolve(host, "LIGERO_HOST", "server.host", "ligero.host", cfg, props, s -> s, "0.0.0.0"),
                resolve(port, "LIGERO_PORT", "server.port", "ligero.port", cfg, props, Integer::parseInt, 8080),
                resolve(contextPath, "LIGERO_CONTEXT_PATH", "server.contextPath", "ligero.contextPath", cfg, props, s -> s, "/"),
                resolve(maxBodyBytes, "LIGERO_MAX_BODY_BYTES", "server.maxBodyBytes", "ligero.maxBodyBytes", cfg, props, Long::parseLong, 10L * 1024 * 1024),
                resolve(virtualThreads, "LIGERO_VIRTUAL_THREADS", "server.virtualThreads", "ligero.virtualThreads", cfg, props, Boolean::parseBoolean, true),
                resolve(gzip, "LIGERO_GZIP", "server.gzip", "ligero.gzip", cfg, props, Boolean::parseBoolean, false),
                resolve(gzipMinBytes, "LIGERO_GZIP_MIN_BYTES", "server.gzipMinBytes", "ligero.gzipMinBytes", cfg, props, Integer::parseInt, 1024),
                resolve(shutdownGrace, "LIGERO_SHUTDOWN_GRACE_SECONDS", "server.shutdownGraceSeconds", "ligero.shutdownGraceSeconds", cfg, props,
                    s -> Duration.ofSeconds(Long.parseLong(s)), Duration.ofSeconds(10)),
                resolve(secureDefaults, "LIGERO_SECURE_DEFAULTS", "security.secureDefaults", "ligero.secureDefaults", cfg, props,
                    Boolean::parseBoolean, true));
        }

        /**
         * Precedence (highest wins): explicit builder call &gt; environment
         * variable ({@code LIGERO_*}) &gt; config source ({@code server.*} —
         * e.g. YAML) &gt; classpath {@code ligero.properties} &gt; default.
         */
        private <T> T resolve(T explicit, String envKey, String sourceKey, String propKey,
                              Config config, Properties props,
                              Function<String, T> parser, T defaultValue) {
            if (explicit != null) {
                return explicit;
            }
            String fromEnv = env.get(envKey);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return parser.apply(fromEnv.trim());
            }
            String fromSource = config.get(sourceKey).orElse(null);
            if (fromSource != null && !fromSource.isBlank()) {
                return parser.apply(fromSource.trim());
            }
            String fromProps = props.getProperty(propKey);
            if (fromProps != null && !fromProps.isBlank()) {
                return parser.apply(fromProps.trim());
            }
            return defaultValue;
        }

        private Properties classpathProperties() {
            if (classpathProperties != null) {
                return classpathProperties;
            }
            Properties props = new Properties();
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(PROPERTIES_RESOURCE)) {
                if (in != null) {
                    props.load(in);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + PROPERTIES_RESOURCE, e);
            }
            classpathProperties = props;
            return props;
        }
    }
}
