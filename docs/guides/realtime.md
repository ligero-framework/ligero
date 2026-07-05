---
sidebar_position: 8
---

# Real-time: SSE & WebSockets

## Server-Sent Events (any engine)

SSE works on every engine, straight from a normal route:

```java
app.get("/events", ctx -> {
    try (SseEmitter sse = ctx.sse()) {
        sse.send("plain data");
        sse.send("update", "{\"n\":1}");          // named event
        sse.send("update", "42", "{\"n\":2}");    // with id (client resume)
        sse.comment("keep-alive");
    }
});
```

The client consumes it with a standard `EventSource`. Thanks to virtual
threads, holding thousands of open SSE connections is cheap.

## WebSockets (Jetty engine)

The WebSocket **API lives in core** (`WsHandler`, `WsSession`); the
**implementation lives in the engine adapter**. Today that's
`ligero-server-jetty` — the JDK engine cannot upgrade protocols and fails at
startup with a clear message if WebSocket routes exist.

```groovy
runtimeOnly 'com.ligero:ligero-server-jetty:0.2.0-SNAPSHOT' // instead of ligero-server-jdk
```

```java
app.websocket("/chat", new WsHandler() {
    @Override public void onConnect(WsSession session) {
        session.attributes().put("joined", Instant.now());
    }

    @Override public void onMessage(WsSession session, String message) {
        session.send("echo: " + message);
    }

    @Override public void onClose(WsSession session, int statusCode, String reason) { }

    @Override public void onError(WsSession session, Throwable error) { }
});
```

HTTP routes and WebSockets share the same port; non-upgrade requests flow
through the normal pipeline untouched.

### Which one do I need?

| | SSE | WebSocket |
|---|---|---|
| Direction | server → client | bidirectional |
| Transport | plain HTTP | protocol upgrade |
| Engine support | all | Jetty adapter |
| Typical use | feeds, notifications, progress | chat, games, collaborative editing |
