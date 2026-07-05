---
sidebar_position: 1
---

# Modules

| Artifact | Depends on | What it is |
|---|---|---|
| `ligero-core` | slf4j-api | API, router, middleware, SPIs, config, validation, SSE, WebSocket API |
| `ligero-server-jdk` | core | Default engine (`com.sun.net.httpserver`, virtual threads) |
| `ligero-server-jetty` | core, Jetty 12 | Alternative engine; WebSocket support |
| `ligero-json` | core, Jackson | `BodyMapper` implementation |
| `ligero-auth` | core | JWT HS256, CSRF, sessions |
| `ligero-template-mustache` | core, JMustache | `TemplateEngine` adapter |
| `ligero-openapi` | core | OpenAPI 3 generation + Swagger UI |
| `ligero-metrics-micrometer` | core, Micrometer | `MetricsCollector` adapter |
| `ligero-test` | core | End-to-end test client |

All published modules ship `module-info.java` (JPMS) with `provides`/`uses`
declarations for the SPIs, plus classic `META-INF/services` files for
classpath users.

A minimal API needs `ligero-core` + one engine; add `ligero-json` for JSON
bodies. Everything else is opt-in.
