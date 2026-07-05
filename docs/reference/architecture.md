---
sidebar_position: 2
---

# Architecture

Ligero's design enforces SOLID principles structurally, not aspirationally:

- **SRP** — path normalization lives only in `PathNormalizer`; matching only in
  `RouteTrie`; dispatch/404/405 semantics only in the pipeline.
- **OCP** — cross-cutting features are middleware. CORS, auth, static files,
  metrics: none of them required touching the core.
- **LSP** — `HttpRequest`/`HttpResponse`/`Context` contracts never depend on the
  concrete implementation (no `instanceof` feature toggles).
- **ISP** — small, focused interfaces (`Handler`, `Middleware`, the SPIs).
- **DIP** — the core depends on abstractions; engines, JSON, templates and
  metrics are adapters resolved via `ServiceLoader` or explicit injection.

```
                 ┌────────────────────────────┐
  your app ────► │  ligero-core (public API)  │
                 │  Ligero, Router, Context,  │
                 │  Middleware, SPIs          │
                 └─────────────┬──────────────┘
                               │ SPIs (ServiceLoader / injection)
     ┌───────────────┬─────────┴───────┬──────────────────┐
     ▼               ▼                 ▼                  ▼
 server engines   ligero-json    ligero-template-*   MetricsCollector
 (jdk, jetty)     (Jackson)      (mustache)          (micrometer)
```

## Request lifecycle

1. The engine adapts the native request/response and calls the root handler.
2. A `Context` is created (path normalized, context path stripped).
3. The middleware chain runs in registration order.
4. The terminal dispatcher matches the route trie, injects path parameters and
   invokes your handler — or produces 404/405/OPTIONS semantics.
5. Any exception is mapped: custom handlers → `HttpException` status →
   opaque 500. Uncommitted responses are finalized.

## Performance notes

- Route matching: segment trie, O(path length) — ~140–215 ns/op with 301
  routes registered (JMH, see the `benchmarks` module).
- Virtual thread per request: blocking I/O in handlers does not tie up carrier
  threads.
- No reflection in the hot path (also keeps GraalVM native-image viable).

The full engineering history — the viability analysis and the phased roadmap
this framework was rebuilt against — lives in the main repo:
[ARCHITECTURE_ANALYSIS.md](https://github.com/ligero-framework/ligero/blob/main/ARCHITECTURE_ANALYSIS.md)
and [ROADMAP.md](https://github.com/ligero-framework/ligero/blob/main/ROADMAP.md).
