# Contributing to Ligero

Thanks for your interest! This project follows a small set of hard rules —
they are what keeps Ligero "ligero".

## Architecture rules (enforced in review)

1. **No new dependencies in `ligero-core`** (only `slf4j-api`). Integrations
   live in adapter modules (`ligero-json`, `ligero-server-*`, `ligero-*`).
2. **Cross-cutting features are middleware**, never core changes (OCP).
3. **Infrastructure goes behind an SPI** (`ServerEngine`, `BodyMapper`,
   `TemplateEngine`, `MetricsCollector`) resolved via `ServiceLoader`.
4. Path normalization/matching only in `PathNormalizer`/`RouteTrie` (DRY).
5. Every change ships with tests. Coverage gates run in the build
   (80 % line coverage in `core`).

## Development

```bash
./gradlew build            # compile, test, coverage gates
./gradlew :examples:run    # demo app on :8080
./gradlew -t :examples:run # dev mode: recompiles and restarts on change
./gradlew :benchmarks:jmh  # router benchmarks (JMH)
```

Java 21+. The build is JPMS-modular; tests run on the classpath.

## Pull requests

1. Fork and branch from `main` (`feature/<short-name>`).
2. Keep commits focused; write messages in imperative mood.
3. Update `CHANGELOG.md` under *Unreleased* and the docs your change touches.
4. CI (build + tests + coverage + dependency review) must be green.

## Reporting bugs / requesting features

Use the issue templates. For security problems see [SECURITY.md](SECURITY.md)
— never a public issue.
