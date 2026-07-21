# Native image (GraalVM) support for Ligero apps

> Status: **supported for the reflection-free stack; documented boundaries for
> the reflective adapters.** The CLI already ships as a native binary; this note
> is about compiling *your app* to a native executable.

## Why Ligero is a good native-image fit

GraalVM `native-image` does a closed-world static analysis: anything reached by
reflection, dynamic proxies or unregistered resources at build time must be
declared in metadata, or it fails at run time. Ligero's design removes most of
that burden:

- **Reflection-free core.** Routing, the middleware pipeline and the `Beans` DI
  container use no runtime reflection (the `arch-test` module enforces this), so
  there is nothing to register for them.
- **Compile-time DI option.** `ligero-processor` generates the `bind(...)`
  wiring at compile time, so even annotated apps have no scanning or reflection.
- **ServiceLoader over scanning.** Engines, body mappers and template engines
  are discovered through `META-INF/services` files — already registered for
  native by `ligero-core`'s `resource-config.json`.

## What is native-ready by construction

These need **no extra metadata** — they are pure JDK + Ligero code:

| Module | Notes |
|---|---|
| `ligero-core` | router, middleware, `Beans`, SSE/WebSocket API, `Events`, cache |
| `ligero-server-jdk` | the `com.sun.net.httpserver` engine (supported by GraalVM) |
| `ligero-scheduler` | virtual-thread scheduling |
| `ligero-resilience` | retry / timeout / circuit-breaker |
| `ligero-auth` | HS256/RS256/ES256 + JWKS (JDK crypto via `java.security`) |
| `ligero-jdbc` | plain JDBC (bring a native-friendly driver, e.g. the pgjdbc-ng or H2) |

`ligero-core` also registers the common app resources for native
(`ligero.yml`/`ligero-*.yml`, `templates/`, `static/`, `db/migration/`,
`META-INF/services/`), so YAML config, server-side templates, static files and
migrations are picked up.

## What needs its own metadata

Libraries that use reflection bring their own GraalVM metadata or need yours:

- **`ligero-json` (Jackson)** — Jackson serializes your DTOs reflectively.
  Register your request/response record types for reflection (a
  `reflect-config.json`, or `@RegisterForReflection`-style build config). Many
  apps avoid this by keeping DTOs as records and adding a small reflect-config.
- **`ligero-jpa` (Hibernate)** and **`ligero-validation` (Hibernate Validator)**
  — reflection-heavy; use the upstream GraalVM support/metadata for these if you
  need them native.
- **`ligero-server-jetty`** — prefer `ligero-server-jdk` for native; Jetty's
  native story is heavier.

## Building a native app

With the GraalVM Gradle plugin (`org.graalvm.buildtools.native`):

```bash
./gradlew nativeCompile        # produces build/native/nativeCompile/<app>
./build/native/nativeCompile/<app>
```

For anything reflective, generate metadata by running the app's tests under the
tracing agent once and committing the output:

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar build/libs/<app>.jar
```

## Roadmap

- Ship a ready `reflect-config` generator hook for `ligero-json` DTOs so record
  bodies are native-ready without the agent.
- Add a `--native` flavor to `ligero new` that wires the GraalVM plugin and a
  starter `reflect-config.json`.
- A CI job that native-compiles a minimal (core + server-jdk) app to guard the
  reflection-free guarantee, extending the arch-test story to the binary.
