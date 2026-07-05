---
sidebar_position: 7
---

# Observability

## Health checks

```java
app.use(HealthMiddleware.builder()
    .path("/health")
    .check("db", () -> dataSource.getConnection().isValid(1))
    .build());
```

`GET /health` → `200 {"status":"UP","checks":{"db":"UP"}}`, or `503` with the
failing checks marked `DOWN`.

## Metrics

`MetricsMiddleware` records method, **matched route pattern** (`/users/{id}`,
not the raw path — bounded cardinality), status and duration through the
`MetricsCollector` SPI.

```java
// dependency-free, expose however you like
InMemoryMetricsCollector metrics = new InMemoryMetricsCollector();
app.use(new MetricsMiddleware(metrics));
app.get("/metrics", ctx -> ctx.json(metrics.snapshot()));

// or Micrometer (ligero-metrics-micrometer) for Prometheus/Datadog/...
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
app.use(new MetricsMiddleware(new MicrometerMetricsCollector(registry)));
```

## Access logs

```java
app.use(new RequestLoggingMiddleware());     // GET /users -> 200 (431 µs)
app.use(RequestLoggingMiddleware.json());    // {"method":"GET","path":"/users","status":200,...}
```

The JSON form includes the request id when `RequestIdMiddleware` runs first.

## Distributed tracing

`RequestIdMiddleware` parses the W3C `traceparent` header and exposes the trace
id as the `traceId` attribute, so your logs and downstream calls can join traces
started by a gateway or another service.

```java
String traceId = ctx.attribute(RequestIdMiddleware.TRACE_ID_ATTRIBUTE);
```
