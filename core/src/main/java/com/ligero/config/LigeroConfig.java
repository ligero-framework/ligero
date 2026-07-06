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
 */
public record LigeroConfig(
    String host,
    int port,
    String contextPath,
    long maxBodyBytes,
    boolean virtualThreads,
    boolean gzip,
    int gzipMinBytes,
    Duration shutdownGrace) {

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
        private Map<String, String> env = System.getenv();
        private Properties classpathProperties;

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

        /** Overrides the environment source — intended for tests. */
        public Builder environment(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public LigeroConfig build() {
            Properties props = classpathProperties();
            return new LigeroConfig(
                resolve(host, "LIGERO_HOST", "ligero.host", props, s -> s, "0.0.0.0"),
                resolve(port, "LIGERO_PORT", "ligero.port", props, Integer::parseInt, 8080),
                resolve(contextPath, "LIGERO_CONTEXT_PATH", "ligero.contextPath", props, s -> s, "/"),
                resolve(maxBodyBytes, "LIGERO_MAX_BODY_BYTES", "ligero.maxBodyBytes", props, Long::parseLong, 10L * 1024 * 1024),
                resolve(virtualThreads, "LIGERO_VIRTUAL_THREADS", "ligero.virtualThreads", props, Boolean::parseBoolean, true),
                resolve(gzip, "LIGERO_GZIP", "ligero.gzip", props, Boolean::parseBoolean, false),
                resolve(gzipMinBytes, "LIGERO_GZIP_MIN_BYTES", "ligero.gzipMinBytes", props, Integer::parseInt, 1024),
                resolve(shutdownGrace, "LIGERO_SHUTDOWN_GRACE_SECONDS", "ligero.shutdownGraceSeconds", props,
                    s -> Duration.ofSeconds(Long.parseLong(s)), Duration.ofSeconds(10)));
        }

        private <T> T resolve(T explicit, String envKey, String propKey, Properties props,
                              Function<String, T> parser, T defaultValue) {
            if (explicit != null) {
                return explicit;
            }
            String fromEnv = env.get(envKey);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return parser.apply(fromEnv.trim());
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
