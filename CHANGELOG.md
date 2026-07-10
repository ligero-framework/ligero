# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.5.0] — 2026-07-10

### Added
- **Startup banner**: `Ligero.start()` now prints a themed banner (the whale +
  wordmark in ANSI colors) followed by a Spring-Boot-style
  `Ligero started in Xs (JVM running for Ys) on http://… · engine · virtual threads`
  line. Colors auto-disable when stdout isn't a terminal or `NO_COLOR` is set;
  `LIGERO_BANNER=plain` forces no color and `LIGERO_BANNER=off` hides it.

### Changed
- **Maven coordinates**: the group id is **`com.ligeroframework`** (e.g.
  `com.ligeroframework:ligero-core:0.5.0`). The Java package is unchanged
  (`com.ligero`). Update your dependency coordinates when upgrading.

## [0.3.0] — 2026-07-09

First release published to Maven Central (`com.ligeroframework:*:0.3.0`).

### Fixed (performance)
- **JDK engine throughput ~8× higher**: `com.sun.net.httpserver` left Nagle's
  algorithm on, which on keep-alive connections combined with delayed ACK to
  add ~40 ms to every response (measured: p50 44 ms, ~350–1000 req/s). The
  engine now enables `sun.net.httpserver.nodelay` (TCP_NODELAY) by default —
  p50 drops to ~4 ms and throughput rises to ~8k req/s on the same hardware,
  putting the zero-dependency engine alongside Jetty/Javalin. Set the system
  property explicitly to override.

### Added (optional compile-time DI)
- **`ligero-processor`**: an *opt-in* annotation processor that reads the
  stereotype annotations and `@Provides` static methods at **compile time**
  and generates the same explicit `bind(...)` wiring you would write by hand
  — a `LigeroModule` per package plus a single `GeneratedModules.all()`. No
  classpath scanning, no runtime reflection: the generated code is byte-for-
  byte what the explicit style produces, so startup and native-image
  behavior are unchanged. Turn it on by adding the `annotationProcessor`
  dependency; remove it and you are back to hand-written wiring.
- Stereotypes gained an optional `as()` attribute (binding key when a class
  implements several interfaces); new `@Provides` (static bean factories,
  e.g. a `DataSource`) and `@Inject` (constructor disambiguation) markers,
  both `SOURCE`-retained (compile-time only).
### Fixed (JSON)
- **`ligero-json` now serializes `java.time`**: `Instant`, `LocalDate`,
  `LocalDateTime`, ... round-trip as ISO-8601 strings (the Jackson
  `jsr310` module is registered, `WRITE_DATES_AS_TIMESTAMPS` off). Unknown
  JSON fields are ignored on read (`FAIL_ON_UNKNOWN_PROPERTIES` off) — a
  friendlier default for evolving APIs.

### Added (data)
- **`ligero-jdbc`**: a tiny, explicit helper over a `DataSource` —
  `query`/`queryOne` map rows to your records via a `RowMapper`, `update`
  returns affected rows, `insert` returns the generated key, and `tx(...)`
  runs a unit of work (commit on success, rollback on any exception). No
  ORM, no reflection; failures wrap the SQL in a `JdbcException`. Verified
  against H2.

### Added (data)
- **`ligero-migrations`**: `Migrations.run(dataSource)` applies Flyway
  migrations from `classpath:db/migration` at startup — one call, before you
  serve traffic. Optional and unopinionated (bring Liquibase or run from CI
  instead). Verified against H2.

### Added (validation)
- **`ligero-validation`**: annotation-based request validation via Jakarta
  Bean Validation (Hibernate Validator). Annotate a request record with the
  standard constraints (`@NotBlank`, `@Email`, `@Min`, …) and
  `Validate.orThrow(ctx.body(T.class))` turns invalid input into a `400`
  listing every violation. Opt-in (Bean Validation uses reflection); the
  core's `ctx.bodyValidator(...)` remains for reflection-free checks.

### Added (HTTP/2)
- **HTTP/2 on the Jetty engine**: `ligero-server-jetty` now speaks HTTP/2
  cleartext (h2c) on the same port as HTTP/1.1 — h2c-capable clients
  negotiate HTTP/2 (upgrade or prior-knowledge), everyone else keeps HTTP/1.1,
  no code change. (The JDK engine can't do HTTP/2 — `com.sun.net.httpserver`
  is HTTP/1.1 only — so HTTP/2 apps use the Jetty engine.)
### Added (YAML config + profiles)
- **`ConfigSource` SPI + `Config`** in core: an optional, format-agnostic
  configuration source (discovered via `ServiceLoader`) plus a typed
  app-facing facade (`Config.load().get/getInt/getBoolean/profile`). The
  core stays dependency-free; framework settings now also read canonical
  `server.*` / `security.*` keys from any source.
- **`ligero-config-yaml`** (SnakeYAML): loads `ligero.yml` and a
  `ligero-<profile>.yml` overlay (profile from `LIGERO_PROFILE` or
  `-Dligero.profile`), flattens nested keys, and interpolates
  `${ENV:-default}`. Precedence stays small and predictable: **builder > env
  (`LIGERO_*`) > YAML (profile > base) > `ligero.properties` > defaults**.
  Pure microservices omit the module and nothing changes.
### Added (data)
- **`ligero-jpa`**: a thin, explicit helper over a JPA
  `EntityManagerFactory` — `Jpa.forUnit("app")`, `jpa.tx(em -> ...)` (commit
  on success, rollback on error), `jpa.read(em -> ...)` with a short-lived
  entity manager, `AutoCloseable`. No open-session-in-view, no thread-locals.
  Only the JPA API is a compile dependency; you bring the provider
  (Hibernate, EclipseLink, ...) and JDBC driver. Verified against Hibernate
  on in-memory H2. Prefer jOOQ/JDBC/Spring Data instead? Bind it as a bean —
  nothing here is mandatory.
### Added (distributed stores)
- **`ligero-redis`**: Redis-backed implementations of the store SPIs so
  rate limits and sessions are shared across app instances.
  `RedisRateLimiterStore` is an atomic fixed-window counter (`INCR` +
  `EXPIRE`); `RedisSessionStore` keeps each session as a Redis hash with a
  sliding TTL. Both sit behind a tiny `RedisOps` seam (Jedis adapter
  provided, fakeable in tests). A new `SessionStore.save(...)` hook (no-op
  default, called by `SessionMiddleware` after each request) lets
  distributed stores flush attribute changes; `Session`'s constructor is
  now public so out-of-package stores can rebuild sessions.

### Added (modules)
- **`LigeroModule` + `Modules.install(...)`**: feature modules Angular-style,
  without the magic — a module is a plain class declaring its beans and its
  routes; the app startup just lists modules. All modules share one `Beans`
  container (cross-module dependencies resolve naturally; duplicates fail
  fast) and wiring lives in the modules, never in the startup class.

### Added (devtools)
- **`ligero-devtools`**: visual debugger for development served at
  `/ligero/dev` — bean dependency graph colored by stereotype plus live
  per-request traces through the layers (controller -> service ->
  repository) with arguments, return values and timing per call, streamed
  over SSE. Interface-typed beans are spied with JDK proxies through the
  `BeanDecorator` hook; production carries zero overhead because the module
  simply isn't on the classpath (`LIGERO_DEVTOOLS=false` also disables it).
- `Beans.graph()` now tags nodes with the stereotype of the implementation
  class when the binding key is an unannotated interface.
### Added (dependency container)
- **`Beans` container** in core: explicit lambda bindings (compiler-verified,
  zero reflection), lazy memoized singletons, eager `start()` that validates
  the whole graph at startup, cycle detection with the full chain in the
  error, `all(supertype)`, reverse-order `close()` of AutoCloseable beans,
  and an instrumentation hook (`BeanDecorator`) for devtools.
- **Stereotype annotations** (`@Component`, `@Service`, `@Repository`,
  `@Controller`): pure metadata — they never drive injection. Together with
  dependency edges captured from real resolution, they produce
  `beans.graph()`, the typed graph that will power the devtools dashboard.
- `app.beans(beans)` exposes every bean to handlers via `ctx.get(type)`.

### Added (extensions batch)
- **`Tracer` SPI + `TracingMiddleware`** in core (vendor-neutral distributed
  tracing) and **`ligero-otel`**: OpenTelemetry adapter that joins incoming
  W3C traces, names spans by matched route and records status/errors. Other
  vendors (New Relic, Datadog, ...) plug in via the same SPI or an OTLP
  exporter.
- **Two more optional template adapters**: `ligero-template-freemarker` and
  `ligero-template-pebble` (HTML auto-escaping in all three; pure
  microservices simply omit them).
- **Secure by default (OWASP baseline)**: security headers plus request-path
  hygiene (null bytes, control characters, encoded traversal -> 400) applied
  automatically; opt out with `secureDefaults(false)` /
  `LIGERO_SECURE_DEFAULTS=false`.
- **OWASP dependency-check**: Gradle task `dependencyCheckAggregate`
  (fails on CVSS >= 7) plus a weekly/dispatchable CI workflow.

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
- **WebSockets**: engine-agnostic API in core (`app.websocket(path, handler)`,
  `WsHandler`/`WsSession`) implemented by the Jetty adapter; the JDK engine
  fails fast with guidance when WebSocket routes are registered.
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
