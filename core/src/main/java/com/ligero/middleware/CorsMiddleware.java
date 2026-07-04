package com.ligero.middleware;

import com.ligero.http.Context;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-Origin Resource Sharing middleware. Handles preflight
 * ({@code OPTIONS} + {@code Access-Control-Request-Method}) requests itself
 * and decorates other responses with the configured CORS headers.
 *
 * <pre>{@code
 * app.use(CorsMiddleware.builder()
 *     .allowOrigins("https://example.com")
 *     .allowMethods("GET", "POST")
 *     .allowHeaders("Content-Type", "Authorization")
 *     .maxAge(Duration.ofHours(1))
 *     .build());
 * }</pre>
 */
public final class CorsMiddleware implements Middleware {

    private final Set<String> allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String exposedHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    private CorsMiddleware(Builder builder) {
        this.allowedOrigins = Set.copyOf(builder.allowedOrigins);
        this.allowedMethods = String.join(", ", builder.allowedMethods);
        this.allowedHeaders = String.join(", ", builder.allowedHeaders);
        this.exposedHeaders = builder.exposedHeaders.isEmpty()
            ? null : String.join(", ", builder.exposedHeaders);
        this.allowCredentials = builder.allowCredentials;
        this.maxAgeSeconds = builder.maxAge.toSeconds();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Permissive configuration: any origin, common methods and headers. */
    public static CorsMiddleware permissive() {
        return builder().build();
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String origin = ctx.header("Origin");
        if (origin == null) {
            chain.proceed();
            return;
        }

        String allowOriginValue = resolveOrigin(origin);
        boolean preflight = "OPTIONS".equals(ctx.method())
            && ctx.header("Access-Control-Request-Method") != null;

        if (allowOriginValue != null) {
            ctx.header("Access-Control-Allow-Origin", allowOriginValue);
            ctx.header("Vary", "Origin");
            if (allowCredentials) {
                ctx.header("Access-Control-Allow-Credentials", "true");
            }
            if (exposedHeaders != null && !preflight) {
                ctx.header("Access-Control-Expose-Headers", exposedHeaders);
            }
        }

        if (preflight) {
            if (allowOriginValue != null) {
                ctx.header("Access-Control-Allow-Methods", allowedMethods);
                ctx.header("Access-Control-Allow-Headers", allowedHeaders);
                ctx.header("Access-Control-Max-Age", String.valueOf(maxAgeSeconds));
                ctx.status(204);
            } else {
                ctx.status(403);
            }
            ctx.res().end();
            return;
        }

        chain.proceed();
    }

    private String resolveOrigin(String origin) {
        if (allowedOrigins.contains("*")) {
            return allowCredentials ? origin : "*";
        }
        return allowedOrigins.contains(origin) ? origin : null;
    }

    public static final class Builder {
        private final Set<String> allowedOrigins = new LinkedHashSet<>(List.of("*"));
        private final Set<String> allowedMethods =
            new LinkedHashSet<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        private final Set<String> allowedHeaders =
            new LinkedHashSet<>(List.of("Content-Type", "Authorization"));
        private final Set<String> exposedHeaders = new LinkedHashSet<>();
        private boolean allowCredentials;
        private Duration maxAge = Duration.ofHours(1);

        public Builder allowOrigins(String... origins) {
            allowedOrigins.clear();
            allowedOrigins.addAll(List.of(origins));
            return this;
        }

        public Builder allowMethods(String... methods) {
            allowedMethods.clear();
            allowedMethods.addAll(List.of(methods));
            return this;
        }

        public Builder allowHeaders(String... headers) {
            allowedHeaders.clear();
            allowedHeaders.addAll(List.of(headers));
            return this;
        }

        public Builder exposeHeaders(String... headers) {
            exposedHeaders.addAll(List.of(headers));
            return this;
        }

        public Builder allowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
            return this;
        }

        public Builder maxAge(Duration maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        public CorsMiddleware build() {
            return new CorsMiddleware(this);
        }
    }
}
