---
sidebar_position: 3
---

# Middleware

Middleware is Ligero's extension mechanism: every cross-cutting concern is a
middleware, never a core change (the open/closed principle applied literally).

```java
@FunctionalInterface
public interface Middleware {
    void handle(Context ctx, Chain chain) throws Exception;
}
```

Call `chain.proceed()` to continue; skip it to short-circuit (you are then
responsible for the response).

```java
app.use((ctx, chain) -> {
    long start = System.nanoTime();
    chain.proceed();
    log.info("{} took {} µs", ctx.path(), (System.nanoTime() - start) / 1000);
});

app.use("/admin", adminOnlyMiddleware);   // path-scoped
```

Middleware runs in registration order, wrapping the router dispatch:
`first-in → … → handler → … → first-out`.

## Built-in middleware

| Middleware | Purpose |
|---|---|
| `RequestIdMiddleware` | `X-Request-Id` passthrough/generation + W3C `traceparent` propagation |
| `RequestLoggingMiddleware` / `.json()` | access log, plain or structured JSON |
| `SecurityHeadersMiddleware` | `nosniff`, `X-Frame-Options`, `Referrer-Policy`, optional HSTS/CSP |
| `CorsMiddleware` | CORS with preflight handling |
| `RateLimitMiddleware` | token bucket, 429 on excess; pluggable `RateLimiterStore` |
| `BasicAuthMiddleware` | HTTP Basic with pluggable validator |
| `StaticFilesMiddleware` | static assets from disk or classpath, traversal-safe, ETag/304 |
| `HealthMiddleware` | `/health` endpoint with named readiness checks |
| `MetricsMiddleware` | per-route counters/latency via the `MetricsCollector` SPI |

From `ligero-auth`: `JwtAuthMiddleware`, `CsrfMiddleware`, `SessionMiddleware`.
From `ligero-openapi`: `OpenApi`.

## Example stack

```java
app.use(new RequestIdMiddleware());
app.use(RequestLoggingMiddleware.json());
app.use(SecurityHeadersMiddleware.defaults());
app.use(CorsMiddleware.builder().allowOrigins("https://app.example").build());
app.use(RateLimitMiddleware.of(100, 100));
app.use(HealthMiddleware.defaults());
app.use(StaticFilesMiddleware.classpath("/assets", "web"));
app.use("/api", JwtAuthMiddleware.of(jwt));
```

## Static files in detail

```java
app.use(StaticFilesMiddleware.external("/static", Path.of("public")));
app.use(StaticFilesMiddleware.classpath("/assets", "web").cacheControl("public, max-age=86400"));
```

- Path traversal (`..`, encoded variants, backslashes) is rejected — covered by tests.
- External files get an `ETag`; `If-None-Match` yields `304`.
- Misses fall through to your routes and 404 handling.
