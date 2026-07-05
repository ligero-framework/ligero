package com.ligero.openapi;

import com.ligero.Ligero;
import com.ligero.http.Context;
import com.ligero.middleware.Middleware;

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
 * syntax); parameters are typed as strings. Schemas/annotations can refine
 * this in a later phase.</p>
 */
public final class OpenApi implements Middleware {

    private final Ligero app;
    private final String title;
    private final String version;
    private final String docPath;
    private final String uiPath;

    private OpenApi(Ligero app, String title, String version, String docPath, String uiPath) {
        this.app = app;
        this.title = title;
        this.version = version;
        this.docPath = docPath;
        this.uiPath = uiPath;
    }

    public static OpenApi of(Ligero app, String title, String version) {
        return new OpenApi(app, title, version, "/openapi.json", null);
    }

    public OpenApi at(String docPath) {
        return new OpenApi(app, title, version, docPath, uiPath);
    }

    /** Opt-in Swagger UI page (loads swagger-ui-dist from a public CDN). */
    public OpenApi withSwaggerUi(String uiPath) {
        return new OpenApi(app, title, version, docPath, uiPath);
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
                operation.put("responses", Map.of("200", Map.of("description", "OK")));
                pathItem.put(method.toLowerCase(), operation);
            }
        });
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", Map.of("title", title, "version", version));
        doc.put("paths", paths);
        return doc;
    }
}
