---
sidebar_position: 1
---

# Routing

Routes map an HTTP method and a path pattern to a [`Handler`](context).
Matching uses a **segment trie** — O(path length), independent of how many
routes you register — with priority *static > parameter > wildcard* and
backtracking.

```java
app.get("/users", ctx -> ...);
app.post("/users", ctx -> ...);
app.put("/users/{id}", ctx -> ...);
app.patch("/users/{id}", ctx -> ...);
app.delete("/users/{id}", ctx -> ...);
app.route("REPORT", "/dav", ctx -> ...);  // any method
app.any("/ping", ctx -> ctx.text("pong")); // all standard methods
```

## Path parameters

```java
app.get("/users/{id}/posts/{postId}", ctx -> {
    String id = ctx.pathParam("id");
    long postId = ctx.pathParamAsLong("postId"); // 400 automatically if not numeric
});
```

## Wildcards

A trailing `*name` captures the remainder of the path:

```java
app.get("/files/*path", ctx -> ctx.text(ctx.pathParam("path")));
// GET /files/css/site.css -> path = "css/site.css"
```

## Route groups

Groups share a prefix and can carry scoped middleware — this replaces
context-path tricks:

```java
app.group("/api/v1", api -> {
    api.use(authMiddleware);                  // only applies under /api/v1
    api.get("/users", ctx -> ...);
    api.group("/admin", admin -> admin.get("/stats", ctx -> ...));
});
```

## Automatic semantics

- **404** — no route matches: JSON error body (customizable via `app.error(404, handler)`).
- **405 + `Allow`** — the path exists under another method.
- **`OPTIONS`** — answered automatically with the `Allow` list when not explicitly routed.
- **Duplicate routes** — registering the same pattern twice throws at startup, not at runtime.

## Precedence example

```java
app.get("/users/{id}", ctx -> ...);  // parameter
app.get("/users/me", ctx -> ...);    // static — wins for GET /users/me
```
