# Contributing to Ligero

Thanks for your interest! This project follows a small set of hard rules —
they are what keeps Ligero "ligero".

## Architecture rules (enforced by `arch-test` + review)

> Rules 1, 3 and 4 (and the reflection-free promise) are checked automatically
> by the [`arch-test`](arch-test) module — ArchUnit fitness functions that run
> as part of `./gradlew build` and fail CI if a change breaks them.


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

## Branching strategy

The same convention applies across all four repos (`ligero`, `ligero-cli`,
`ligero-examples`, `ligero-docs`).

- **Trunk-based.** `main` is always releasable and protected — never push to it
  directly. Every change lands through a reviewed PR.
- **Branch from the latest `main`.** Name it by intent:
  `feature/<name>`, `fix/<name>`, `docs/<name>`, `chore/<name>`,
  `release/x.y.z`, `hotfix/x.y.z`.
- **One PR = one logical change, based directly on `main`.** Keep it small.
- **Avoid stacked PRs** (a PR whose base is another feature branch). Deep stacks
  interact badly with squash-merges and manual merge order. If you genuinely
  must stack, merge strictly **bottom-up** and delete each branch on merge so
  GitHub retargets the next one.
- **Rebase on `main` before merging.** For the shared "registration" files,
  resolve conflicts by keeping the **union** of both sides:
  - `CHANGELOG.md` — keep both entries
  - `settings.gradle` — keep both `include` lines
  - `gradle/libs.versions.toml` — keep both version/library lines (de-dupe)
  - `build.gradle` `coverageMinimums` — merge both module keys
- **Squash-merge**, then delete the branch.

## Pull requests

1. Branch from `main` (see above); keep commits focused, messages imperative.
2. Update `CHANGELOG.md` under *Unreleased* and the docs your change touches.
3. CI (build + tests + coverage + dependency review) must be green.

Cutting a release? Follow [RELEASING.md](RELEASING.md).

## Reporting bugs / requesting features

Use the issue templates. For security problems see [SECURITY.md](SECURITY.md)
— never a public issue.
