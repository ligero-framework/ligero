---
sidebar_position: 2
---

# The Context

Every handler receives a `Context` — one object wrapping the request, the
response, path parameters and per-request state.

## Reading the request

```java
ctx.method();                      // "GET"
ctx.path();                        // "/users/42" (normalized, context path stripped)
ctx.header("User-Agent");          // case-insensitive
ctx.queryParam("q");               // first value ("" if present without value)
ctx.queryParams("tag");            // List<String> for repeated params
ctx.pathParam("id");               // String
ctx.pathParamAsInt("id");          // int — automatic 400 on bad input
ctx.cookie("session");             // request cookie
ctx.remoteAddress();               // client IP
```

## Bodies

```java
String raw   = ctx.bodyAsString();
byte[] bytes = ctx.bodyAsBytes();
MyDto dto    = ctx.body(MyDto.class);       // JSON, requires ligero-json

// Validation: collects all failures into one 400 response
MyDto valid = ctx.bodyValidator(MyDto.class)
    .check(d -> d.name() != null, "name is required")
    .check(d -> d.age() >= 0, "age must be positive")
    .get();

// Forms
String name = ctx.formParam("name");        // x-www-form-urlencoded or multipart

// File uploads
Multipart multipart = ctx.multipart();
Multipart.UploadedFile file = multipart.file("upload");
file.filename(); file.contentType(); file.content();
```

Bodies are size-limited (10 MiB by default, configurable) — oversized requests
get `413` before your handler runs.

## Writing the response

```java
ctx.status(201)
   .header("X-Custom", "v")
   .json(created);                 // application/json

ctx.text("plain");                 // text/plain
ctx.html("<h1>hi</h1>");           // text/html
ctx.redirect("/new");              // 302
ctx.redirect("/new", 308);         // any 3xx
ctx.setCookie(Cookie.of("sid", "abc").withSecure(true).withSameSite("Strict"));
ctx.render("profile", Map.of("user", user)); // via a TemplateEngine adapter
```

## Content negotiation

```java
if (ctx.accepts("application/json")) { ... }
String best = ctx.preferredType(List.of("application/json", "text/html"));
```

`Accept` q-values and wildcards (`*/*`, `text/*`) are honored.

## Attributes and services

Attributes carry per-request state between middleware and handlers; services
are app-wide singletons registered explicitly (no reflection):

```java
// registration (startup)
app.register(UserRepository.class, new JdbcUserRepository(dataSource));

// in a handler
UserRepository repo = ctx.get(UserRepository.class);
ctx.attribute("startNanos", System.nanoTime());
Long started = ctx.attribute("startNanos");
```

## Legacy style

The `(req, res)` handler signature from early versions still compiles:

```java
app.get("/old/{id}", (req, res) -> res.json(Map.of("id", req.getPathParams().get("id"))));
```
