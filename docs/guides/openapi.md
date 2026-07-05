---
sidebar_position: 10
---

# OpenAPI

`ligero-openapi` generates an OpenAPI 3 document **from the routes you already
registered** — no annotations:

```groovy
implementation 'com.ligero:ligero-openapi:0.2.0-SNAPSHOT'
```

```java
Ligero app = Ligero.create(8080);
app.use(OpenApi.of(app, "My API", "1.0.0")     // serves GET /openapi.json
    .withSwaggerUi("/docs"));                   // optional UI page

app.get("/users/{id}", ctx -> ...);
```

- Path patterns translate directly (`{id}` is already OpenAPI syntax) and become
  required string path parameters.
- Wildcard routes are skipped (no OpenAPI equivalent).
- The Swagger UI page loads `swagger-ui-dist` from a public CDN — enable it only
  where that is acceptable.

Schema refinement (request/response models, descriptions) is planned as a
later, annotation-free layer.
