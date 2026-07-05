---
sidebar_position: 11
---

# Testing

## End-to-end with ligero-test

`ligero-test` starts your app on an **ephemeral port** and drives it over real
HTTP:

```groovy
testImplementation 'com.ligero:ligero-test:0.2.0-SNAPSHOT'
```

```java
@Test
void createsUsers() {
    try (LigeroTest test = LigeroTest.create(app -> {
        app.post("/users", ctx -> ctx.status(201).json(Map.of("ok", true)));
    })) {
        LigeroTest.TestResponse response = test.post("/users")
            .json("{\"name\":\"Ada\"}")
            .execute();

        assertEquals(201, response.status());
        assertTrue(response.body().contains("ok"));
        assertEquals("application/json", response.header("content-type"));
    }
}
```

`LigeroTest.start(app)` attaches to a pre-built app (configure it with port 0).

## Unit tests without a server

Because the engine is an SPI, you can inject an in-memory fake and exercise the
whole pipeline — routing, middleware, error mapping — without opening a socket:

```java
class FakeEngine implements ServerEngine {
    HttpHandler root;
    public void start(EngineConfig cfg, HttpHandler root) { this.root = root; }
    public void stop(Duration grace) {}
    public int port() { return 0; }
}

FakeEngine engine = new FakeEngine();
app.engine(engine);
app.start();
engine.root.handle(fakeRequest, fakeResponse);  // drive it directly
```

This is exactly how the framework tests itself (158 tests, both styles).
