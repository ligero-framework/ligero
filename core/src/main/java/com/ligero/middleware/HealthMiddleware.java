package com.ligero.middleware;

import com.ligero.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Opt-in health endpoint (liveness/readiness). Responds on its configured
 * path with {@code 200 {"status":"UP", ...}} — or {@code 503} when any
 * registered check fails — and lets every other request pass through.
 *
 * <pre>{@code
 * app.use(HealthMiddleware.builder()
 *     .path("/health")
 *     .check("db", dataSource::isValid)
 *     .build());
 * }</pre>
 */
public final class HealthMiddleware implements Middleware {

    private final String path;
    private final Map<String, Supplier<Boolean>> checks;

    private HealthMiddleware(String path, Map<String, Supplier<Boolean>> checks) {
        this.path = path;
        this.checks = checks;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Plain liveness endpoint at {@code /health} with no checks. */
    public static HealthMiddleware defaults() {
        return builder().build();
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (!"GET".equals(ctx.method()) || !path.equals(ctx.path())) {
            chain.proceed();
            return;
        }
        StringBuilder checksJson = new StringBuilder();
        boolean up = true;
        for (Map.Entry<String, Supplier<Boolean>> check : checks.entrySet()) {
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(check.getValue().get());
            } catch (RuntimeException e) {
                ok = false;
            }
            up &= ok;
            if (checksJson.length() > 0) {
                checksJson.append(',');
            }
            checksJson.append('"').append(check.getKey()).append("\":\"")
                      .append(ok ? "UP" : "DOWN").append('"');
        }
        String body = checks.isEmpty()
            ? "{\"status\":\"" + (up ? "UP" : "DOWN") + "\"}"
            : "{\"status\":\"" + (up ? "UP" : "DOWN") + "\",\"checks\":{" + checksJson + "}}";
        ctx.status(up ? 200 : 503).res().contentType("application/json").send(body);
    }

    public static final class Builder {
        private String path = "/health";
        private final Map<String, Supplier<Boolean>> checks = new LinkedHashMap<>();

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** Adds a named readiness check; false or a thrown exception means DOWN. */
        public Builder check(String name, Supplier<Boolean> check) {
            checks.put(name, check);
            return this;
        }

        public HealthMiddleware build() {
            return new HealthMiddleware(path, Map.copyOf(checks));
        }
    }
}
