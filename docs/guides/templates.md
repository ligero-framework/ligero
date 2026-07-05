---
sidebar_position: 9
---

# Templates

Server-side rendering goes through the `TemplateEngine` SPI. The first adapter
is `ligero-template-mustache` (JMustache — a single small dependency):

```groovy
runtimeOnly 'com.ligero:ligero-template-mustache:0.2.0-SNAPSHOT'
```

Put templates on the classpath under `templates/`:

```
src/main/resources/templates/profile.mustache
```

```mustache
<h1>Hello {{name}}!</h1>
<p>You have {{count}} messages.</p>
```

```java
app.get("/profile/{name}", ctx ->
    ctx.render("profile", Map.of("name", ctx.pathParam("name"), "count", 3)));
```

HTML escaping is on by default; templates are compiled once and cached.

## Writing another adapter

Implement `com.ligero.spi.TemplateEngine` and register it via
`META-INF/services/com.ligero.spi.TemplateEngine` (or `app.templateEngine(...)`).
That's the whole contract:

```java
public interface TemplateEngine {
    String render(String templateName, Map<String, Object> model);
}
```
