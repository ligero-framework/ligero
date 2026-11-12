<div align="center">
  <img src="assets/ligero.svg" alt="Ligero Logo" width="200">
  
  # Ligero Framework
  
  [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html) [![Documentation](https://img.shields.io/badge/Documentation-Online-brightgreen)](https://doc.ligeroframework.com/) [![Maven Central](https://img.shields.io/maven-central/v/com.ligeroframework/ligero-core.svg)](https://search.maven.org/search?q=g:com.ligeroframework)

  <p><em>A lightweight Java web framework for modern applications</em></p>
</div>

## Overview

Ligero is a lightweight, minimalist web framework for building modern web applications and APIs in Java. Designed with simplicity and performance in mind, Ligero provides a clean, expressive API without the complexity of traditional enterprise frameworks.

```java
// Create a new Ligero app
Ligero app = Ligero.create(8080);

// Define a simple route
app.get("/hello/{name}", ctx ->
    ctx.json(Map.of("message", "Hello, " + ctx.pathParam("name") + "!")));

// Start the server
app.start();
```

## Features

- ⚡️ **Lightweight & Fast**: zero dependencies in the core, served on Java 21 virtual threads
- 🧱 **Typed controllers**: endpoints as plain Java methods — `@Get("/{id}") Product get(@PathParam long id)` — with parameters, body and return type checked by the compiler; the routing code is generated at build time (no reflection)
- 🧩 **Simple API**: fluent, Express-inspired `Context` handlers (`ctx.json(...)`, `ctx.pathParam(...)`) for when a lambda is all you need
- 🛣️ **Expressive Routing**: trie-based matching with `{params}`, `*wildcards`, route groups, automatic 405/OPTIONS
- 🔗 **Middleware**: composable pipeline with built-ins for CORS, security headers, rate limiting, Basic auth, request id, access logging and static files
- 🚨 **Error Handling**: `HttpException` hierarchy, custom exception/status handlers, no stack-trace leaks
- 🛡️ **Secure by default**: OWASP-aligned baseline (security headers + request hygiene) enabled automatically, disable with `secureDefaults(false)` or `LIGERO_SECURE_DEFAULTS=false`
- ⚙️ **Typed Config**: builder > env vars (`LIGERO_*`) > `ligero.properties` > defaults
- 🔌 **Extensible (SPI)**: pluggable server engines (JDK & Jetty adapters included), JSON mappers and template engines via `ServiceLoader`
- 🔐 **Auth**: JWT (HS256), Basic auth, stateless CSRF and signed-cookie sessions (`ligero-auth`)
- 📈 **Observability**: health endpoint, per-route metrics (Micrometer adapter), structured access logs, distributed tracing via the `Tracer` SPI (`ligero-otel` for OpenTelemetry; any vendor pluggable)
- 🔁 **Real-time**: Server-Sent Events in core (`ctx.sse()`), WebSockets via the Jetty engine (`app.websocket(path, handler)`)
- 📜 **OpenAPI**: generated from your routes, with opt-in Swagger UI (`ligero-openapi`)
- 🧪 **Testable**: in-memory fake engine for unit tests, `ligero-test` for fluent end-to-end tests
- 🧭 **Dependency injection & modules**: explicit, compile-checked wiring (no reflection) with an optional compile-time processor; feature modules (`LigeroModule`) that keep wiring out of `main()`
- 🔬 **Visual devtools**: `/ligero/dev` — an interactive console to fire any route ("try it out") and watch it flow through your beans, with the JSON in/out and timing of every layer, plus a live bean graph (`ligero-devtools`)
- 🗄️ **Data, your way**: `ligero-jdbc` (SQL → records, no ORM), `ligero-jpa` (Hibernate), and `ligero-migrations` (Flyway) — mix and match
- ⚙️ **YAML config + profiles**: `ligero.yml` with per-profile overlays and `${ENV:-default}` interpolation (`ligero-config-yaml`)
- ✅ **Validation**: annotation-based request validation → automatic 400 (`ligero-validation`)
- 🌐 **HTTP/2**: h2c on the Jetty engine, one line to switch
- 📈 **Scales out**: Redis-backed rate-limit and session stores shared across instances (`ligero-redis`)

> 📖 **New to Ligero?** Follow the **[Learning Path](https://doc.ligeroframework.com/learning-path)** — a guided route from your first route to a production service.

## Modules

Everything except `ligero-core` and an engine is optional — add a module when
you need it, carry nothing you don't.

| Artifact | What it is |
|---|---|
| `ligero-core` | Public API, router, middleware, DI (`Beans`), feature modules, SPIs — zero deps (slf4j-api only) |
| `ligero-server-jdk` | Default `ServerEngine` (JDK http server, virtual threads, TCP_NODELAY) |
| `ligero-server-loom` | Ligero's **own** `ServerEngine`: HTTP/1.1 + WebSockets written from scratch on sockets and virtual threads — zero dependencies, strict parsing, the fastest of the three |
| `ligero-server-jetty` | Alternative `ServerEngine` on Jetty 12 — adds **HTTP/2 (h2c)** and WebSockets |
| `ligero-json` | Jackson `BodyMapper` (`ctx.body()` / `ctx.json()`), with `java.time` support |
| `ligero-processor` | Optional compile-time annotation processor that generates the DI wiring |
| `ligero-devtools` | `/ligero/dev` dashboard — "try it out" request console, per-request flow graph (JSON + timing per layer), live bean graph |
| `ligero-config-yaml` | `ligero.yml` + profiles, `${ENV:-default}` interpolation (`ConfigSource` SPI) |
| `ligero-jdbc` | Tiny SQL helper — query → record, transactions, no ORM |
| `ligero-jpa` | Thin JPA/Hibernate helper (bring your own provider) |
| `ligero-migrations` | Schema migrations at startup via Flyway |
| `ligero-validation` | Annotation-based request validation (Bean Validation / Hibernate Validator) → 400 |
| `ligero-redis` | Distributed rate-limit + session stores for scaling out |
| `ligero-auth` | JWT (HS256), CSRF, sessions |
| `ligero-template-mustache` | `TemplateEngine` adapter (JMustache) |
| `ligero-template-freemarker` | `TemplateEngine` adapter (FreeMarker) |
| `ligero-template-pebble` | `TemplateEngine` adapter (Pebble, Twig/Jinja syntax) |
| `ligero-otel` | `Tracer` adapter for OpenTelemetry (vendor-neutral tracing) |
| `ligero-openapi` | OpenAPI 3 generation + Swagger UI |
| `ligero-metrics-micrometer` | Metrics adapter for Micrometer registries |
| `ligero-test` | End-to-end testing utilities |

## Installation

> **Note:** Ligero is not published to Maven Central yet (planned — see
> [ROADMAP.md](ROADMAP.md)). Until then, build it locally with
> `./gradlew publishToMavenLocal` and depend on the snapshot.

### Gradle

```groovy
implementation 'com.ligeroframework:ligero-core:0.2.0-SNAPSHOT'       // API
runtimeOnly    'com.ligeroframework:ligero-server-jdk:0.2.0-SNAPSHOT' // server engine (SPI)
runtimeOnly    'com.ligeroframework:ligero-json:0.2.0-SNAPSHOT'       // JSON mapper (SPI, optional)
```

### Maven

```xml
<dependency>
    <groupId>com.ligeroframework</groupId>
    <artifactId>ligero-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.ligeroframework</groupId>
    <artifactId>ligero-server-jdk</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.ligeroframework</groupId>
    <artifactId>ligero-json</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

### 1. Create a new Java project

Set up a new Java project with your favorite build tool.

### 2. Add Ligero as a dependency

Add Ligero to your project dependencies as shown above.

### 3. Create your application

```java
import com.ligero.Ligero;
import java.util.Map;

public class Application {
    public static void main(String[] args) throws Exception {
        // Create a new Ligero app
        Ligero app = Ligero.create(8080);

        // Define routes
        app.get("/", ctx -> ctx.text("Welcome to Ligero!"));

        app.get("/api/hello/{name}", ctx -> ctx.json(Map.of(
            "message", "Hello, " + ctx.pathParam("name") + "!",
            "timestamp", System.currentTimeMillis()
        )));

        // Start the server
        app.start();
        System.out.println("Server started at http://localhost:" + app.port());
    }
}
```

### 4. Run your application

Run your application and visit `http://localhost:8080` in your browser.

## Core Concepts

### Routes

```java
// Basic routes — handlers receive a Context
app.get("/users", ctx -> ctx.json(users));
app.post("/users", ctx -> { /* ... */ });
app.put("/users/{id}", ctx -> { /* ... */ });
app.delete("/users/{id}", ctx -> { /* ... */ });

// Path parameters (typed access responds 400 automatically on bad input)
app.get("/users/{id}/posts/{postId}", ctx -> {
    long userId = ctx.pathParamAsLong("id");
    String postId = ctx.pathParam("postId");
    // ...
});

// Wildcards capture the rest of the path
app.get("/files/*path", ctx -> ctx.text(ctx.pathParam("path")));

// Route groups share a prefix (and optionally middleware)
app.group("/api/v1", api -> {
    api.get("/users", ctx -> ctx.json(users));
    api.group("/admin", admin -> admin.get("/stats", ctx -> ctx.json(stats)));
});

// Custom 404 rendering
app.error(404, ctx -> ctx.json(Map.of("error", "Not found", "path", ctx.path())));
```

The classic `(req, res)` handler style keeps working:

```java
app.get("/legacy/{id}", (req, res) -> res.json(Map.of("id", req.getPathParams().get("id"))));
```

### Typed controllers

For anything beyond a handful of routes, declare endpoints as methods. Inputs
are typed parameters, the output is the return type, and the method is unit
testable by calling it:

```java
@Controller("/api/products")
public final class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @Get
    List<Product> list(@QueryParam(defaultValue = "1") int page, @QueryParam Category category) {
        return products.list(page, category);
    }

    @Get("/{id}")
    Optional<Product> get(@PathParam long id) {          // empty -> 404
        return products.find(id);
    }

    @Post
    Response<Product> create(@Body @Valid NewProduct body) {   // invalid -> 400
        Product created = products.create(body);
        return Response.created("/api/products/" + created.id(), created);
    }

    @Delete("/{id}")
    void delete(@PathParam long id) {                     // -> 204
        products.delete(id);
    }
}
```

| You write | You get |
|---|---|
| `@PathParam long id`, `@QueryParam int page`, `@HeaderParam("X-Tenant") String t` | converted values; a bad or missing one answers `400` naming the parameter |
| `@QueryParam Category c` (enum), `UUID`, `BigDecimal`, `List<String>` | the same, for richer types |
| `@Body NewProduct body` (+ `@Valid` with `ligero-validation`) | the deserialized, validated record |
| return a record / `List` / `Map` | JSON, `200` |
| return `String` | `text/plain` |
| return `Optional<T>` | the value, or `404` when empty |
| `void` | `204` |
| return `Response<T>` or add `@Status(201)` | explicit status and headers |
| a `Context ctx` parameter | the full low-level API, when you need it |

Add the processor and install the generated modules — that is the whole setup:

```groovy
annotationProcessor 'com.ligeroframework:ligero-processor:<version>'
```

```java
Ligero app = Ligero.create(8080);
Modules.install(app, GeneratedModules.all());   // beans wired, routes mounted
app.start();
```

Nothing is discovered at runtime. The processor writes a
`ProductControllerRoutes` class next to your controller containing the same
`app.route(...)` calls you would have typed by hand (open it in
`build/generated` to see exactly what runs), and a mistake — a `@PathParam`
the route does not declare, an unsupported type, a private method — is a
**compile error**. Prefer explicit wiring? Call
`ProductControllerRoutes.mount(app, controller)` yourself. The lambda style
below keeps working and mixes freely with typed controllers.

### Requests & Responses

```java
app.post("/api/data", ctx -> {
    MyDto dto = ctx.body(MyDto.class);           // JSON body (via ligero-json)
    String raw = ctx.bodyAsString();             // raw body
    String param = ctx.queryParam("param");      // query parameter
    String agent = ctx.header("User-Agent");     // header (case-insensitive)
    String session = ctx.cookie("session");      // cookie

    ctx.status(201)                              // status code
       .header("X-Custom-Header", "value")      // header
       .json(Map.of("status", "created"));      // JSON response
});
```

### Middleware

Everything cross-cutting is a middleware — the core never changes (open/closed):

```java
app.use(new RequestIdMiddleware());              // X-Request-Id propagation
app.use(new RequestLoggingMiddleware());         // access log
app.use(SecurityHeadersMiddleware.defaults());   // nosniff, frame options, ...
app.use(CorsMiddleware.builder()
    .allowOrigins("https://example.com")
    .allowMethods("GET", "POST")
    .build());
app.use(RateLimitMiddleware.of(100, 100));       // 100 req burst, 100 req/s
app.use(StaticFilesMiddleware.external("/static", Path.of("public")));
app.use("/admin", BasicAuthMiddleware.of("Admin", store::matches)); // path-scoped

// Custom middleware
app.use((ctx, chain) -> {
    long start = System.nanoTime();
    chain.proceed();
    log.info("{} took {} µs", ctx.path(), (System.nanoTime() - start) / 1000);
});
```

### Error Handling

```java
// Throw HttpException (or subclasses) from anywhere in the pipeline
app.get("/users/{id}", ctx -> {
    User user = repo.find(ctx.pathParamAsLong("id"))
        .orElseThrow(() -> new NotFoundException("No such user"));
    ctx.json(user);
});

// Map your own exception types
app.exception(SQLException.class, (e, ctx) ->
    ctx.status(503).json(Map.of("error", "database unavailable")));
```

Unexpected exceptions become an opaque `500` — details go to the log, never to the client.

### Validation

```java
app.post("/users", ctx -> {
    User user = ctx.bodyValidator(User.class)
        .check(u -> u.name() != null && !u.name().isBlank(), "name is required")
        .check(u -> u.age() >= 0, "age must be positive")
        .get(); // throws -> 400 with all collected messages
    ctx.status(201).json(repo.save(user));
});
```

### Configuration

```java
Ligero app = Ligero.create(LigeroConfig.builder()
    .port(8080)
    .contextPath("/api")
    .maxBodyBytes(5 * 1024 * 1024)
    .gzip(true)
    .shutdownGrace(Duration.ofSeconds(30))
    .build());
```

Every value can also come from `LIGERO_*` environment variables or a
classpath `ligero.properties` file (builder > env > properties > defaults).

## Advanced Usage

### Pluggable Server Engines (SPI)

The core never instantiates a server: engines implement the `ServerEngine`
SPI and are discovered via `ServiceLoader` (`ligero-server-jdk` is the
default, running on virtual threads). For tests you can inject an in-memory
fake and drive the whole pipeline without opening a socket:

```java
app.engine(new FakeEngine());
app.bodyMapper(new JacksonBodyMapper());
```

#### The Loom engine — Ligero's own server

`ligero-server-loom` does not wrap anybody else's HTTP server: it reads and
writes HTTP/1.1 (and WebSockets) directly on sockets. Swap the dependency and
nothing else changes:

```groovy
runtimeOnly 'com.ligeroframework:ligero-server-loom:<version>'   // instead of ligero-server-jdk
```

- **Fast.** One thread per connection with blocking I/O — no dispatcher, no
  hand-off between threads; requests are parsed from a per-connection buffer
  and a response is normally one write. The first 256 concurrent connections
  get a dedicated platform thread, the rest run on virtual threads. See
  [`perf/`](perf/README.md) for how it compares.
- **Complete.** Keep-alive and pipelining, chunked requests and streaming
  responses (SSE), `Expect: 100-continue`, `HEAD`, gzip, graceful shutdown,
  and WebSockets (handshake, fragmentation, ping/pong, close codes).
- **Strict.** Bounded head, header count, body and connections; a deadline for
  slow request heads; and anything ambiguous is refused — `Content-Length`
  with `Transfer-Encoding`, conflicting lengths, folded headers, a missing
  `Host` — which is what request smuggling relies on. Response headers are
  validated so application data cannot split a response.
- **Zero dependencies**, like the core.

It speaks HTTP/1.1; terminate TLS and HTTP/2 at your proxy or load balancer,
or use the Jetty engine for h2c.

### Graceful Shutdown

```java
Ligero app = Ligero.create(8080);
// ...
app.start();
Runtime.getRuntime().addShutdownHook(new Thread(app::stop)); // drains in-flight requests
```

## Examples

Check out the [examples directory](examples/src/main/java/com/ligero/examples) for more examples of using Ligero.

## Roadmap

The detailed, phased roadmap lives in [ROADMAP.md](ROADMAP.md). Highlights:

- **Phase 0 (v0.2)** — Stabilization: bug fixes, dependency cleanup, tests, CI
- **Phase 1 (v0.3)** — Extensible core: middleware chain, server/JSON SPIs, trie-based router
- **Phase 2 (v0.4)** — Web essentials: static files, CORS, templates, validation, config
- **Phase 3 (v0.5)** — Production readiness: security, observability, WebSockets
- **Phase 4 (v0.6–1.0)** — Ecosystem: testing utilities, OpenAPI, CLI, API freeze

## Documentation & Tooling

- 📚 Full documentation: [ligero-framework/ligero-docs](https://github.com/ligero-framework/ligero-docs) (published at [doc.ligeroframework.com](https://doc.ligeroframework.com/))
- 🛠️ Project scaffolding: [ligero-framework/ligero-cli](https://github.com/ligero-framework/ligero-cli) — `ligero new my-api`

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Inspired by modern web frameworks like Express.js
- Built with love by the Ligero team
