package com.ligero.openapi;

import com.ligero.Ligero;
import com.ligero.http.Context;
import com.ligero.middleware.Middleware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAPI 3 document generation from the routes registered in a
 * {@link Ligero} app, served as a middleware:
 *
 * <pre>{@code
 * app.use(OpenApi.of(app, "My API", "1.0.0")); // GET /openapi.json
 * }</pre>
 *
 * <p>Route patterns translate directly ({@code {id}} is already OpenAPI
 * syntax); path parameters are typed as strings. Attach request/response
 * body schemas — generated from your record types — with {@link #model} and
 * {@link #describe}:</p>
 *
 * <pre>{@code
 * app.use(OpenApi.of(app, "My API", "1.0.0")
 *     .describe("POST", "/users", op -> op
 *         .summary("Create a user")
 *         .requestBody(NewUser.class)
 *         .response(201, User.class)));
 * }</pre>
 */
public final class OpenApi implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(OpenApi.class);

    private final Ligero app;
    private final String title;
    private final String version;
    private final String docPath;
    private final String uiPath;
    // Shared across at()/withSwaggerUi() copies so registrations survive chaining.
    private final Map<String, Object> componentSchemas;
    private final Map<String, Operation> operations;

    private OpenApi(Ligero app, String title, String version, String docPath, String uiPath,
                    Map<String, Object> componentSchemas, Map<String, Operation> operations) {
        this.app = app;
        this.title = title;
        this.version = version;
        this.docPath = docPath;
        this.uiPath = uiPath;
        this.componentSchemas = componentSchemas;
        this.operations = operations;
    }

    public static OpenApi of(Ligero app, String title, String version) {
        log.info("OpenAPI spec at {}", "/openapi.json");
        return new OpenApi(app, title, version, "/openapi.json", null,
            new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public OpenApi at(String docPath) {
        log.info("OpenAPI spec at {}", docPath);
        return new OpenApi(app, title, version, docPath, uiPath, componentSchemas, operations);
    }

    /** Opt-in Swagger UI page (loads swagger-ui-dist from a public CDN). */
    public OpenApi withSwaggerUi(String uiPath) {
        log.info("Swagger UI at {}", uiPath);
        return new OpenApi(app, title, version, docPath, uiPath, componentSchemas, operations);
    }

    /**
     * Registers a record type as a reusable component schema (also pulling in any
     * nested record types), so it can be referenced from request/response bodies.
     */
    public OpenApi model(Class<?> type) {
        componentSchemas.put(Schemas.name(type), Schemas.of(type, componentSchemas));
        return this;
    }

    /**
     * Attaches request/response body types and a summary to an operation, keyed by
     * HTTP method and route pattern (e.g. {@code describe("POST", "/users", ...)}).
     * Referenced types are registered as component schemas automatically.
     */
    public OpenApi describe(String method, String path, java.util.function.Consumer<Operation> customizer) {
        Operation operation =
            operations.computeIfAbsent(method.toUpperCase() + " " + path, k -> new Operation());
        customizer.accept(operation);
        if (operation.requestBodyType != null) {
            model(operation.requestBodyType);
        }
        operation.responseTypes.values().forEach(this::model);
        return this;
    }

    /** Request/response metadata for one operation. */
    public static final class Operation {
        private String summary;
        private Class<?> requestBodyType;
        private final Map<Integer, Class<?>> responseTypes = new java.util.LinkedHashMap<>();

        public Operation summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Operation requestBody(Class<?> type) {
            this.requestBodyType = type;
            return this;
        }

        public Operation response(int status, Class<?> type) {
            responseTypes.put(status, type);
            return this;
        }
    }

    private static Map<String, Object> bodyRef(Class<?> type) {
        return Map.of("content", Map.of("application/json",
            Map.of("schema", Map.of("$ref", "#/components/schemas/" + Schemas.name(type)))));
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (!"GET".equals(ctx.method())) {
            chain.proceed();
            return;
        }
        if (docPath.equals(ctx.path())) {
            ctx.json(document());
            return;
        }
        if (uiPath != null && uiPath.equals(ctx.path())) {
            ctx.html(swaggerUiPage());
            return;
        }
        chain.proceed();
    }

    private String swaggerUiPage() {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>%s — API docs</title>
              <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
              <script>
                SwaggerUIBundle({ url: '%s', dom_id: '#swagger-ui' });
              </script>
            </body>
            </html>
            """.formatted(title, docPath);
    }

    /** Builds the OpenAPI document as a plain map (serialized by the BodyMapper). */
    public Map<String, Object> document() {
        Map<String, Object> paths = new TreeMap<>();
        app.routes().forEach((method, routePaths) -> {
            for (String route : routePaths) {
                if (route.contains("*")) {
                    continue; // wildcard routes have no OpenAPI equivalent
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> pathItem =
                    (Map<String, Object>) paths.computeIfAbsent(route, r -> new LinkedHashMap<>());
                Map<String, Object> operation = new LinkedHashMap<>();
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (String segment : route.split("/")) {
                    if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                        parameters.add(Map.of(
                            "name", segment.substring(1, segment.length() - 1),
                            "in", "path",
                            "required", true,
                            "schema", Map.of("type", "string")));
                    }
                }
                if (!parameters.isEmpty()) {
                    operation.put("parameters", parameters);
                }

                Operation meta = operations.get(method.toUpperCase() + " " + route);
                if (meta != null && meta.summary != null) {
                    operation.put("summary", meta.summary);
                }
                if (meta != null && meta.requestBodyType != null) {
                    operation.put("requestBody", bodyRef(meta.requestBodyType));
                }
                if (meta != null && !meta.responseTypes.isEmpty()) {
                    Map<String, Object> responses = new LinkedHashMap<>();
                    meta.responseTypes.forEach((status, type) -> {
                        Map<String, Object> response = new LinkedHashMap<>(bodyRef(type));
                        response.put("description", "OK");
                        responses.put(String.valueOf(status), response);
                    });
                    operation.put("responses", responses);
                } else {
                    operation.put("responses", Map.of("200", Map.of("description", "OK")));
                }
                pathItem.put(method.toLowerCase(), operation);
            }
        });
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", Map.of("title", title, "version", version));
        doc.put("paths", paths);
        if (!componentSchemas.isEmpty()) {
            doc.put("components", Map.of("schemas", new LinkedHashMap<>(componentSchemas)));
        }
        return doc;
    }
}
