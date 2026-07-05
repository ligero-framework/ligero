---
sidebar_position: 4
---

# Error Handling

## HttpException

Throw from anywhere in the pipeline; the framework maps it to a response:

```java
app.get("/users/{id}", ctx -> {
    User user = repo.find(ctx.pathParamAsLong("id"))
        .orElseThrow(() -> new NotFoundException("No such user"));
    ctx.json(user);
});
```

Built-in subclasses: `BadRequestException` (400), `UnauthorizedException` (401),
`ForbiddenException` (403), `NotFoundException` (404), `MethodNotAllowedException`
(405), `PayloadTooLargeException` (413), `TooManyRequestsException` (429) —
or `new HttpException(status, message)` for anything else.

Default rendering is a uniform JSON body:

```json
{"status": 404, "error": "No such user"}
```

## Custom exception handlers

Map your own exception types (subclasses match too):

```java
app.exception(SQLException.class, (e, ctx) ->
    ctx.status(503).json(Map.of("error", "database unavailable")));
```

## Custom status pages

```java
app.error(404, ctx -> ctx.html("<h1>Lost?</h1>"));
app.error(405, ctx -> ctx.json(Map.of("error", "wrong method")));
```

## Unexpected exceptions

Anything not mapped becomes an **opaque 500**: the message and stack trace go
to the log, never to the client. If an exception handler itself fails, a plain
500 is emitted and both failures are logged.

## Validation errors

`ctx.bodyValidator(...)` throws `ValidationException` (a 400) carrying all
failed checks:

```json
{"status": 400, "error": "Validation failed: name is required; age must be positive"}
```
