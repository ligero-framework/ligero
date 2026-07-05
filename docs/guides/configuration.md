---
sidebar_position: 5
---

# Configuration

`LigeroConfig` is an immutable record; values resolve with precedence
**builder > environment variables > classpath `ligero.properties` > defaults**.

```java
Ligero app = Ligero.create(LigeroConfig.builder()
    .host("0.0.0.0")
    .port(8080)                          // 0 = ephemeral (tests)
    .contextPath("/api")
    .maxBodyBytes(5 * 1024 * 1024)       // -> 413 beyond this
    .virtualThreads(true)
    .gzip(true)
    .gzipMinBytes(1024)
    .shutdownGrace(Duration.ofSeconds(30))
    .build());
```

| Builder | Env var | Property | Default |
|---|---|---|---|
| `host` | `LIGERO_HOST` | `ligero.host` | `0.0.0.0` |
| `port` | `LIGERO_PORT` | `ligero.port` | `8080` |
| `contextPath` | `LIGERO_CONTEXT_PATH` | `ligero.contextPath` | `/` |
| `maxBodyBytes` | `LIGERO_MAX_BODY_BYTES` | `ligero.maxBodyBytes` | 10 MiB |
| `virtualThreads` | `LIGERO_VIRTUAL_THREADS` | `ligero.virtualThreads` | `true` |
| `gzip` | `LIGERO_GZIP` | `ligero.gzip` | `false` |
| `gzipMinBytes` | `LIGERO_GZIP_MIN_BYTES` | `ligero.gzipMinBytes` | `1024` |
| `shutdownGrace` | `LIGERO_SHUTDOWN_GRACE_SECONDS` | `ligero.shutdownGraceSeconds` | 10 s |

`Ligero.create()` (no arguments) loads defaults + properties + env, so a container
deployment configures itself entirely through `LIGERO_*` variables.

## Graceful shutdown

`app.stop()` drains in-flight requests within `shutdownGrace`. Typical wiring:

```java
app.start();
Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
```
