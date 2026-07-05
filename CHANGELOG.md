# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased] — 0.2.0-SNAPSHOT

Execution of the full roadmap (phases 0–4); the only items left open depend
on external credentials/processes or future milestones — see
[ROADMAP.md](ROADMAP.md) for per-item status.

### Added (second batch — roadmap completion)
- **JPMS**: `module-info.java` in every published module, with
  `provides`/`uses` for the SPIs.
- **`ligero-auth`**: JWT HS256 sign/verify (no external deps, rejects
  `alg=none`), `JwtAuthMiddleware` with role checks, stateless CSRF
  protection (double-submit cookie), and cookie sessions (HMAC-signed id,
  `SessionStore` SPI).
- **`ligero-server-jetty`**: second `ServerEngine` on Jetty 12 core —
  the integration suite runs unchanged on both engines, validating the SPI.
- **`ligero-template-mustache`**: first `TemplateEngine` adapter (JMustache).
- **`ligero-test`**: fluent end-to-end test client (`LigeroTest`).
- **`ligero-openapi`**: OpenAPI 3 generation from registered routes plus
  opt-in Swagger UI.
- **`ligero-metrics-micrometer`**: `MetricsCollector` adapter for Micrometer.
- **Core**: `multipart/form-data` parsing (`ctx.multipart()`), `Accept`
  content negotiation (`ctx.accepts()`/`preferredType()`), Server-Sent
  Events (`ctx.sse()`), minimal DI (`app.register`/`ctx.get`),
  `HealthMiddleware`, `MetricsMiddleware` + in-memory collector,
  JSON access log, W3C `traceparent` propagation, `RateLimiterStore` SPI,
  matched-route pattern exposed to middleware.
- **Benchmarks**: JMH module for router matching (~140–215 ns/op with 301
  routes).
- **Quality/community**: GraalVM reachability metadata, `SECURITY.md`,
  `CONTRIBUTING.md`, code of conduct, issue/PR templates,
  dependency-review workflow, conditional snapshot-publishing CI job.

### Fixed
- **Path parameters now work end-to-end** (`{id}` was silently dropped by the
  real server because injection depended on a specific request subtype).
- Removed global `--enable-preview` compilation: published classes no longer
  require preview mode in consumer JVMs.
- Path normalization now collapses double slashes and lives in a single
  `PathNormalizer` (previously triplicated with diverging behavior).
- `redirect()` respects the committed state, supports 301/303/307/308 and no
  longer corrupts the response after a prior `send()`.
- Query parameters without value map to `""` instead of `null`; repeated
  keys are preserved (`getQueryParamValues`).
- HTTP request headers are case-insensitive (RFC 9110) and computed once.
- Internal errors no longer leak exception messages or stack traces to
  clients; details go to the log only.

### Added
- **Middleware pipeline** (`app.use(...)`, path-scoped variants) with
  built-ins: request id, access logging, CORS, security headers, rate
  limiting (token bucket), HTTP Basic auth and static file serving with
  path-traversal protection, ETag/304 and Cache-Control.
- **`Context` API** for handlers (`ctx.pathParam`, `ctx.body(Class)`,
  `ctx.json`, cookies, form params, attributes, typed path params with
  automatic 400). Legacy `(req, res)` handlers keep working.
- **SPIs**: `ServerEngine` (server adapters), `BodyMapper` (JSON) and
  `TemplateEngine` (templates), resolved via `ServiceLoader` or explicit
  injection.
- **Trie-based router** with static > parameter > wildcard priority,
  backtracking, duplicate-route detection, automatic 405 + `Allow` and
  automatic `OPTIONS`.
- **Exception mapping**: `HttpException` hierarchy, `app.exception(...)`,
  `app.error(status, ...)`, uniform JSON error bodies.
- **Route groups**: `app.group("/api/v1", ...)` with nesting and scoped
  middleware.
- **Typed configuration** `LigeroConfig` (record + builder) with precedence
  builder > env vars (`LIGERO_*`) > classpath `ligero.properties` > defaults.
- Request body size limit (413) and optional gzip compression.
- Virtual threads (Project Loom) as the default execution model.
- Body validation helper (`ctx.bodyValidator(...)` → 400 with all failures).
- Test suite (unit + end-to-end) with JaCoCo coverage gates and GitHub
  Actions CI.
- `LICENSE` (Apache 2.0).

### Changed
- Modules reorganized per the target architecture: API in `ligero-core`
  (zero dependencies except slf4j-api), Jackson isolated in `ligero-json`,
  JDK engine in `ligero-server-jdk`; `http` and `router` folded into core.
- Published artifacts renamed to `ligero-core`, `ligero-json`,
  `ligero-server-jdk` (previously the generic `core`, `json`, `server`).
- Debug `System.out.println` calls replaced with SLF4J logging.
- Dependencies: removed unused Javalin (and its Jetty tree) from the router
  and the 2007-era `com.sun.net.httpserver:http` artifact; Jackson updated
  to 2.18.x; versions managed via Gradle version catalog.

### Removed
- `examples` module is no longer published to Maven repositories.
