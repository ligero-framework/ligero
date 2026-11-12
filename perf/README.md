# HTTP benchmark

The same products API — `GET /products`, `GET /products/{id}`,
`POST /products` over an in-memory store — served by:

| App | Stack |
|---|---|
| `apps/ligero-app` | Ligero, lambda routes; engine chosen with `-Pengine=loom\|jdk\|jetty` |
| `apps/ligero-typed-app` | Ligero, typed controllers, JDK engine |
| `apps/javalin-app` | Javalin 6 on Jetty |
| `apps/springboot-app` | Spring Boot 3.4, Web MVC on Tomcat |

`run.py` builds each one (`installDist`), starts it cold several times in
interleaved rounds, drives it with [`Load.java`](Load.java) — a raw-socket,
keep-alive HTTP/1.1 client on virtual threads, cheap enough not to be the
bottleneck — and compares the medians.

## What is checked

1. **No regression.** The build under test must not be more than 10 % slower
   than the baseline build of Ligero in any scenario, on the JDK engine and —
   once the baseline has it — on the Loom engine.
2. **Standing.** Overall throughput (geometric mean of the three scenarios)
   of Ligero on its own Loom engine, divided by each other framework's, must
   not fall below the floor in [`thresholds.json`](thresholds.json). When
   Ligero gains ground, raise the floor so the gain is locked in.

Startup time, memory, the Jetty engine and typed controllers are reported but
not gated.

A reference run (4 CPUs, 32 connections, medians of 3 rounds, requests/s):

| | GET list | GET by id | POST | Overall |
|---|--:|--:|--:|--:|
| Ligero, Loom engine | 188,813 | 173,212 | 129,087 | 161,621 |
| Javalin 6 (Jetty) | 59,451 | 60,681 | 50,025 | 56,511 |
| Ligero, Jetty engine | 44,794 | 44,613 | 35,569 | 41,424 |
| Ligero, JDK engine | 38,196 | 38,299 | 31,016 | 35,667 |
| Spring Boot 3.4 (Tomcat) | 29,338 | 29,114 | 23,394 | 27,136 |

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
  what that engine can reach — the reason the Loom engine exists.
- The Loom engine serves its first 256 concurrent connections on dedicated
  platform threads and the rest on virtual threads. The benchmark's 32
  connections all take the fast path. With 1,000 busy connections on the same
  machine it measured 57–110 thousand requests/s against Javalin's 73
  thousand: still competitive, but the lead is largest when the busy
  connections fit in the dedicated budget
  (`LoomServerEngine.Limits.withDedicatedThreads`).
