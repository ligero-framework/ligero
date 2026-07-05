---
sidebar_position: 2
---

# Quickstart

A complete application in one file:

```java
import com.ligero.Ligero;
import com.ligero.http.NotFoundException;
import com.ligero.middleware.RequestLoggingMiddleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Application {

    record User(Long id, String name) {}

    static final Map<Long, User> USERS = new ConcurrentHashMap<>();
    static final AtomicLong IDS = new AtomicLong();

    public static void main(String[] args) throws Exception {
        Ligero app = Ligero.create(8080);
        app.use(new RequestLoggingMiddleware());

        app.group("/api/users", api -> {
            api.get("", ctx -> ctx.json(USERS.values()));

            api.get("/{id}", ctx -> {
                User user = USERS.get(ctx.pathParamAsLong("id"));
                if (user == null) throw new NotFoundException("No such user");
                ctx.json(user);
            });

            api.post("", ctx -> {
                User body = ctx.bodyValidator(User.class)
                    .check(u -> u.name() != null && !u.name().isBlank(), "name is required")
                    .get();
                long id = IDS.incrementAndGet();
                User created = new User(id, body.name());
                USERS.put(id, created);
                ctx.status(201).json(created);
            });
        });

        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        System.out.println("http://localhost:" + app.port());
    }
}
```

Try it:

```bash
curl localhost:8080/api/users
curl -X POST localhost:8080/api/users -d '{"name":"Ada"}'
curl localhost:8080/api/users/1
curl -X POST localhost:8080/api/users -d '{"name":""}'   # 400 with validation detail
curl localhost:8080/api/users/nope                        # 400 (typed path param)
curl -X PUT localhost:8080/api/users                      # 405 with Allow header
```

Everything you saw — validation, typed params, JSON errors, 405 semantics —
is default behavior. Next: [Routing](../guides/routing).
