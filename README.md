# Ligero CLI

Scaffolding CLI for the [Ligero web framework](https://github.com/ligero-framework/ligero).
Zero runtime dependencies, Java 21+.

## Install

```bash
./gradlew installDist
export PATH="$PATH:$(pwd)/build/install/ligero/bin"
```

## Usage

```bash
ligero new my-api --package com.acme.api   # ready-to-run project with tests
cd my-api && gradle run                     # http://localhost:8080

ligero generate controller User             # CRUD controller with validation
ligero version
```

`ligero new` generates: Gradle build wired to `ligero-core`/`ligero-server-jdk`/`ligero-json`,
an `Application` with routes and middleware, and an end-to-end test using `ligero-test`.

> **Note:** the generated project resolves Ligero from `mavenLocal()` until the framework
> is published to Maven Central — run `./gradlew publishToMavenLocal` in the framework repo first.

## License

Apache 2.0
