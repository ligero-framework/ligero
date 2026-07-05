---
sidebar_position: 1
slug: /
---

# Introduction

**Ligero** (Spanish for *lightweight*) is a minimalist web framework for Java 21+
built around three ideas:

1. **Zero dependencies in the core** — `ligero-core` depends only on `slf4j-api`.
   Everything else (JSON, server engines, templates, metrics) is a pluggable adapter.
2. **Virtual threads by default** — blocking, easy-to-read handler code that scales
   like async code, courtesy of Project Loom.
3. **An Express-style API** — routes, middleware and a `Context` object. No annotations,
   no reflection, no classpath scanning, no magic.

```java
Ligero app = Ligero.create(8080);

app.get("/hello/{name}", ctx ->
    ctx.json(Map.of("message", "Hello, " + ctx.pathParam("name") + "!")));

app.start();
```

## When to use Ligero

- REST APIs and microservices where startup time and footprint matter.
- Services that want plain, debuggable Java instead of framework magic.
- Teaching/learning HTTP fundamentals on the JVM.

## When *not* to use it

Ligero deliberately has **no ORM, no DI container with scanning, no reactive types**.
If you need the full Jakarta EE surface or Spring's ecosystem integrations,
use those — Ligero optimizes for the other end of the spectrum.

## How it compares

| | Ligero | Javalin | Spring Boot |
|---|---|---|---|
| Core dependencies | 0 (slf4j-api) | Jetty | dozens |
| Programming model | handlers + middleware | handlers | annotations + DI |
| Virtual threads | default | optional | optional |
| Startup | < 100 ms | ~300 ms | seconds |

Continue with [Installation](getting-started/installation).
