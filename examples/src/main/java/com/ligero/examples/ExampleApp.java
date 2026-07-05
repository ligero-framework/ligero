package com.ligero.examples;

import com.ligero.Ligero;
import com.ligero.http.NotFoundException;
import com.ligero.middleware.CorsMiddleware;
import com.ligero.middleware.HealthMiddleware;
import com.ligero.middleware.InMemoryMetricsCollector;
import com.ligero.middleware.MetricsMiddleware;
import com.ligero.middleware.RequestIdMiddleware;
import com.ligero.middleware.RequestLoggingMiddleware;
import com.ligero.middleware.SecurityHeadersMiddleware;
import com.ligero.openapi.OpenApi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo application showcasing the Ligero API: middleware, route groups,
 * path parameters, JSON bodies, validation and error handling.
 *
 * <p>Run with {@code ./gradlew :examples:run} and try:</p>
 * <pre>
 * curl http://localhost:8080/
 * curl http://localhost:8080/api/v1/users
 * curl http://localhost:8080/api/v1/users/1
 * curl -X POST http://localhost:8080/api/v1/users -d '{"name":"Ada"}'
 * </pre>
 */
public final class ExampleApp {

    record User(Long id, String name) {
    }

    private static final Map<Long, User> USERS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    public static void main(String[] args) throws IOException {
        USERS.put(IDS.incrementAndGet(), new User(IDS.get(), "Ada Lovelace"));

        Ligero app = Ligero.create(8080);

        // Cross-cutting concerns are middleware — the core stays closed for
        // modification, open for extension.
        app.use(new RequestIdMiddleware());
        app.use(new RequestLoggingMiddleware());
        app.use(SecurityHeadersMiddleware.defaults());
        app.use(CorsMiddleware.permissive());
        app.use(HealthMiddleware.defaults());                       // GET /health
        InMemoryMetricsCollector metrics = new InMemoryMetricsCollector();
        app.use(new MetricsMiddleware(metrics));
        app.use(OpenApi.of(app, "Ligero Example API", "0.2.0")      // GET /openapi.json
            .withSwaggerUi("/docs"));                               // GET /docs
        app.get("/metrics", ctx -> ctx.json(metrics.snapshot()));

        app.get("/", ctx -> ctx.text("Welcome to Ligero!"));

        app.get("/events", ctx -> {
            try (var sse = ctx.sse()) {
                for (int i = 1; i <= 3; i++) {
                    sse.send("tick", String.valueOf(i));
                }
            }
        });

        app.group("/api/v1", api -> {
            api.get("/users", ctx -> ctx.json(List.copyOf(USERS.values())));

            api.get("/users/{id}", ctx -> {
                long id = ctx.pathParamAsLong("id");
                User user = USERS.get(id);
                if (user == null) {
                    throw new NotFoundException("User " + id + " does not exist");
                }
                ctx.json(user);
            });

            api.post("/users", ctx -> {
                User body = ctx.bodyValidator(User.class)
                    .check(u -> u.name() != null && !u.name().isBlank(), "name is required")
                    .get();
                long id = IDS.incrementAndGet();
                User created = new User(id, body.name());
                USERS.put(id, created);
                ctx.status(201).json(created);
            });

            api.delete("/users/{id}", ctx -> {
                USERS.remove(ctx.pathParamAsLong("id"));
                ctx.status(204).res().end();
            });
        });

        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        System.out.println("Example app running at http://localhost:" + app.port());
    }
}
