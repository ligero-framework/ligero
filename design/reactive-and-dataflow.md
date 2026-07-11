# Design analysis — reactive execution & data-flow (dependency DAG)

> Status: **analysis / RFC**, not a commitment. Explores two related but
> distinct capabilities the framework could grow, grounded in what Ligero is
> today (Java 21, a virtual thread per request, blocking `Handler`s).
>
> Two questions, treated separately:
> 1. **Reactivity** — should handlers be able to run *asynchronously*
>    (`CompletableFuture` / non-blocking) or is the virtual-thread model
>    enough?
> 2. **Data-flow DAG** — a first-class way to declare a graph of async data
>    sources, resolve the independent ones in parallel, and aggregate — the
>    "backend-for-frontend / API aggregator" use case.

---

## 0. Where Ligero is today

| Aspect | Today |
|---|---|
| Java baseline | **21** (`sourceCompatibility = VERSION_21`) — records, virtual threads, `StructuredTaskScope` (preview) all available |
| Threading | **One virtual thread per request** — `Executors.newVirtualThreadPerTaskExecutor()` in both `JdkServerEngine` and `JettyServerEngine` |
| Handler contract | `void handle(Context ctx) throws Exception` — **synchronous & blocking** |
| Concurrency inside a handler | none provided — a handler that calls three services calls them **sequentially** |
| I/O style | blocking (`ctx.body(...)`, JDBC via `ligero-jdbc`, HTTP clients the user brings) |

The important consequence: **Ligero is already "reactive" in the property
that matters most** — it does not block an OS thread while waiting on I/O. A
virtual thread parked on a blocking call costs ~a few hundred bytes of heap,
not an OS thread. Throughput under I/O-bound load is bounded by the downstream,
not by a thread pool. What Ligero does **not** have yet is a way to express
*concurrency within a single request* (fan-out) or to *return before the work
is done* (streaming/async completion).

---

## 1. Reactivity: `CompletableFuture` vs. virtual threads (Loom)

### 1.1 The two philosophies

- **Async / reactive (Reactor, RxJava, `CompletableFuture` chains).** The
  handler returns *immediately* with a promise of a future value; the request
  thread is released and the result is delivered by a callback when I/O
  completes. Scales on few OS threads, but code becomes "coloured": every
  async call propagates `CompletableFuture<T>`/`Mono<T>` up the stack, `try/catch`
  becomes `.exceptionally(...)`, `ThreadLocal` breaks, stack traces are useless,
  and debuggers can't follow the flow. (This is *exactly* the pain the devtools
  trace tries to reconstruct — worth remembering.)

- **Virtual threads (Project Loom).** Write ordinary blocking, sequential code;
  the JVM parks the *virtual* thread (not the carrier OS thread) on blocking
  I/O. You get async scalability with synchronous code: real stack traces,
  `try/catch`, `ThreadLocal`, step-debugging all work. This is Ligero's current
  model.

### 1.2 Assessment for Ligero

Ligero's identity is "the lightweight, no-magic Java framework": explicit
wiring, readable stack traces, zero runtime reflection, a debugger-friendly
devtools story. **A Reactor-style reactive stack is philosophically opposed to
that.** It would:

- fork the whole API surface (a `ReactiveHandler` returning `Mono`, reactive
  middleware, a reactive `Context`), doubling the framework;
- break the devtools recorder (the JDK-proxy trace assumes a call returns on
  the same thread; reactive pipelines hop threads);
- pull in a large dependency (Reactor) against the zero-dep goal.

The JEP that introduced virtual threads was explicitly motivated by *letting
server frameworks keep the simple thread-per-request model without paying for
it*. Ligero already took that path. **Recommendation: do not adopt a reactive
(Reactor) core.** Instead, offer *lightweight async affordances* on top of the
virtual-thread model for the two things it can't express today.

### 1.3 What to add instead (small, optional, non-colouring)

**(a) Async handler return — interop, not a new paradigm.**
Allow a handler to hand back a `CompletableFuture` (or a `Supplier` run on a
virtual thread) for the cases where the caller already has one (an async SDK, a
reactive DB driver). The engine simply *joins* it on the request's virtual
thread — no reactive plumbing leaks into middleware or `Context`:

```java
// additive overload; existing void handlers unchanged
app.get("/quote", ctx -> ctx.async(() -> pricing.quoteAsync(ctx.pathParam("id"))));
// ctx.async(CompletableFuture<T>) -> serializes T as JSON when it completes,
// still on this request's carrier, so devtools tracing keeps working.
```

**(b) Structured fan-out inside a handler.** The real gap. See Part 2 — this is
where parallelism belongs, and `StructuredTaskScope` gives it to us cleanly
without reactive types.

**(c) Streaming responses** already exist (`ctx.sse()`); a future `ctx.stream()`
for chunked/NDJSON would round out "reactive-looking" output without Reactor.

**Bottom line for Part 1:** stay virtual-thread-native. Add `ctx.async(...)` for
interop and lean on structured concurrency for parallelism. Revisit Reactor only
if a concrete user needs backpressure over an infinite stream — a niche Ligero
can decline.

---

## 2. Data-flow DAG (parallel aggregation / BFF)

### 2.1 The use case

A client wants Ligero as an **aggregation layer**: one request fans out to many
async sources (HTTP APIs, DBs, caches), some depending on the output of others,
and the independent ones should run **in parallel**; when all resolve, the
handler assembles one response. Concretely:

```
                 ┌─► user(id) ─────────────┐
request(id) ─────┤                          ├─► profile = merge(user, orders, prefs)
                 ├─► orders(id) ─┐          │
                 │               └─► enrich(orders) ─┘
                 └─► prefs(id) ────────────┘
```

`user`, `orders`, `prefs` have no dependencies on each other → run concurrently.
`enrich(orders)` needs `orders` → runs after it. The framework should resolve
this **dependency DAG** with maximum parallelism and short-circuit on failure.

Today a handler would call these sequentially (latency = sum). With a DAG
resolver, latency ≈ the **critical path** (longest chain), not the sum.

### 2.2 The right primitive: structured concurrency (JDK 21+)

`java.util.concurrent.StructuredTaskScope` (preview in 21, targeting stable)
is *purpose-built* for this and is a natural fit for the virtual-thread model:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User>   user   = scope.fork(() -> users.find(id));   // each on its
    Subtask<Orders> orders = scope.fork(() -> orders.of(id));    // own virtual
    Subtask<Prefs>  prefs  = scope.fork(() -> prefs.of(id));     // thread
    scope.join().throwIfFailed();                                // all-or-nothing
    return assemble(user.get(), orders.get(), prefs.get());
}
```

This already gives parallel fan-out with all-or-nothing error handling,
cancellation of siblings on first failure, and a clean stack. Ligero can expose
it directly (document the pattern) **and** build a thin declarative layer for
the DAG-with-dependencies case, where hand-writing scopes gets tedious.

### 2.3 Proposed declarative API — `DataGraph`

A small, zero-dependency module (`ligero-dataflow`) that lets you *declare*
nodes and their dependencies and resolves them with a `StructuredTaskScope`
under the hood. Design goals: no reactive types, values are plain `T`, each
node runs on its own virtual thread, independent nodes run in parallel,
each node computed **at most once** (memoized), first failure cancels the rest.

```java
DataGraph g = DataGraph.create();

Node<User>   user   = g.supply("user",   () -> users.find(id));
Node<Orders> orders = g.supply("orders", () -> orders.of(id));
Node<Prefs>  prefs  = g.supply("prefs",  () -> prefs.of(id));
// a node that depends on another — only runs once `orders` is ready
Node<Enriched> enriched = g.derive("enriched", orders, o -> catalog.enrich(o));

Profile profile = g.resolve(user, enriched, prefs,        // returns when all done
    (u, e, p) -> new Profile(u, e, p));
```

Resolution algorithm (topological, level-parallel):

1. Build the DAG from declared dependencies; **reject cycles** at `resolve()`.
2. Repeatedly: take all nodes whose dependencies are satisfied, `fork` them into
   the current `StructuredTaskScope` (each = one virtual thread), `join`.
3. Memoize each node's result; feed it to dependents.
4. On any failure: `ShutdownOnFailure` cancels siblings; surface the original
   exception (real stack trace) — no `.exceptionally` needed.
5. Optional per-node **timeout** (`scope.joinUntil(deadline)`) and **fallback**
   (`node.orElse(default)`).

### 2.4 Ergonomic integration points

- **`ctx.graph()`** — a `DataGraph` scoped to the request, so nodes can read
  path/query params and the result is serialized as the response.
- **Bean-aware nodes** — `g.supply(SomeService.class, s -> s.load(id))` resolves
  the bean from the `Beans` container, so the DAG participates in DI.
- **Devtools is a natural home for visualizing this.** The dashboard we just
  built already draws a request's bean graph and highlights the executed path.
  A `DataGraph` *is* a per-request DAG with real timings — devtools could render
  the resolution graph (nodes, parallel levels, each node's duration and the
  critical path) almost for free using the existing trace/graph machinery. This
  is a strong differentiator: **"declare your aggregation, then watch it resolve
  in parallel in devtools."**

### 2.5 Prior art (what to borrow / avoid)

- **GraphQL DataLoader / Netflix DGS** — batching + per-request caching of async
  loads. Borrow the *memoization* and *batching* ideas; avoid coupling to
  GraphQL.
- **Facebook Haxl / Stitch** — applicative DAG of data fetches resolved in
  rounds. Our "level-parallel topological resolve" is the same shape.
- **Spring `@Async` / Reactor `Mono.zip`** — the reactive answer; we get the
  same parallelism with blocking code + structured concurrency and no coloured
  functions.

---

## 3. Recommendation & phased roadmap

| Phase | Deliverable | Risk |
|---|---|---|
| **1 (now)** | Document the `StructuredTaskScope` fan-out pattern in the guides; it needs **no framework code** (JDK 21 preview flag) | low |
| **2** | `ctx.async(CompletableFuture<T>)` + `ctx.async(Supplier<T>)` overloads on the engine, for async-SDK interop | low |
| **3** | `ligero-dataflow`: `DataGraph`/`Node` declarative resolver over `StructuredTaskScope`, DI-aware, memoized, cancel-on-failure, per-node timeout/fallback | medium |
| **4** | Devtools renders the per-request data-flow DAG (parallel levels, node durations, critical path) reusing the trace/graph UI | medium |
| **non-goal** | A Reactor/`Mono`-based reactive core, backpressure over infinite streams | — |

**Guiding principle:** keep blocking, sequential, debuggable code as the default;
buy parallelism with *structured concurrency*, not with a reactive runtime. This
preserves everything Ligero optimizes for — readability, real stack traces,
zero magic, and a devtools story that can actually *see* the execution — while
unlocking the high-value aggregation/BFF scenario.

### Open questions

- `StructuredTaskScope` is a **preview** API in 21 (stabilizing in later LTS).
  Ship `ligero-dataflow` as opt-in and gate on the JDK, or wait for the stable
  API? (Recommendation: prototype now behind `--enable-preview`, GA when the
  API stabilizes.)
- Batching (DataLoader-style N+1 collapsing) — worth it for v1, or a later add?
- Do we expose the DAG as data (for devtools/OpenTelemetry spans) from day one?
  (Recommendation: yes — emit one span per node; near-free observability.)
