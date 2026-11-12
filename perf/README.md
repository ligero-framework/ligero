# HTTP benchmark

The same products API — `GET /products`, `GET /products/{id}`,
`POST /products` over an in-memory store — served by:

| App | Stack |
|---|---|
| `apps/ligero-app` | Ligero, lambda routes, JDK engine (or Jetty with `-Pengine=jetty`) |
| `apps/ligero-typed-app` | Ligero, typed controllers, JDK engine |
| `apps/javalin-app` | Javalin 6 on Jetty |
| `apps/springboot-app` | Spring Boot 3.4, Web MVC on Tomcat |

`run.py` builds each one (`installDist`), starts it cold several times in
interleaved rounds, drives it with [`Load.java`](Load.java) — a raw-socket,
keep-alive HTTP/1.1 client on virtual threads, cheap enough not to be the
bottleneck — and compares the medians.

## What is checked

1. **No regression.** The build under test must not be more than 10 % slower
   than the baseline build of Ligero in any scenario.
2. **Standing.** Overall throughput (geometric mean of the three scenarios)
   divided by each other framework's must not fall below the floor in
   [`thresholds.json`](thresholds.json). When Ligero gains ground, raise the
   floor so the gain is locked in.

Startup time, memory, the Jetty engine and typed controllers are reported but
not gated.

The [`Benchmark` workflow](../.github/workflows/benchmark.yml) runs this on
every pull request against the PR's target branch and publishes the table in
the job summary.

## Run it locally

```bash
# from the repository root: publish the two builds to compare
git stash -u && git checkout main
./gradlew publishToMavenLocal -Pversion=0.0.0-base-SNAPSHOT -x javadoc
git checkout - && git stash pop
./gradlew publishToMavenLocal -Pversion=0.0.0-pr-SNAPSHOT -x javadoc

python3 perf/run.py --candidate 0.0.0-pr-SNAPSHOT --baseline 0.0.0-base-SNAPSHOT
```

Useful flags: `--rounds 5 --duration 10` for steadier numbers,
`--skip-others` to compare only the two Ligero builds, `--no-gates` to
report without failing. `python3 perf/run.py --help` lists the rest.

## Reading the numbers

- Everything runs on one machine, load driver included, so absolute figures
  depend on the hardware; **compare rows of the same run**, never across runs.
- Shared CI runners are noisy. Interleaved rounds and medians absorb most of
  it, and the regression tolerance covers the rest — a failure well inside the
  tolerance band is worth a re-run before investigating.
- The JDK engine sits on `com.sun.net.httpserver`. That server alone, with no
  framework on top, tops out below Jetty on the same machine, which bounds
  what the default engine can reach. Closing that gap is engine work, not
  routing work.
