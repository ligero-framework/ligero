package com.ligero.examples.platform;

import com.ligero.Ligero;
import com.ligero.middleware.CorsMiddleware;
import com.ligero.middleware.HealthMiddleware;
import com.ligero.middleware.InMemoryMetricsCollector;
import com.ligero.middleware.MetricsMiddleware;
import com.ligero.middleware.RequestIdMiddleware;
import com.ligero.middleware.RequestLoggingMiddleware;
import com.ligero.middleware.SecurityHeadersMiddleware;
import com.ligero.openapi.OpenApi;

/**
 * Cross-cutting concerns shared by every feature: the middleware stack and
 * the operational endpoints. Cross-cutting behaviour is middleware — the
 * core stays closed for modification, open for extension.
 */
public final class Platform {

    private Platform() {
    }

    public static void install(Ligero app) {
        InMemoryMetricsCollector metrics = new InMemoryMetricsCollector();

        app.use(new RequestIdMiddleware());
        app.use(new RequestLoggingMiddleware());
        app.use(SecurityHeadersMiddleware.defaults());
        app.use(CorsMiddleware.permissive());
        app.use(new MetricsMiddleware(metrics));
        app.use(HealthMiddleware.defaults());                           // GET /health
        app.use(OpenApi.of(app, "Ligero Example API", "0.2.0")          // GET /openapi.json
            .withSwaggerUi("/docs"));                                   // GET /docs

        app.get("/metrics", ctx -> ctx.json(metrics.snapshot()));
    }
}
