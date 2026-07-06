# Hoja de Ruta — Ligero Framework

> **Documento canónico de la hoja de ruta.** Sustituye y consolida las notas previas de `ROUTE.md` y `FRAMEWORK_IMPROVEMENTS.md`.
> El diagnóstico técnico que justifica cada fase está en [ARCHITECTURE_ANALYSIS.md](ARCHITECTURE_ANALYSIS.md).
> Última actualización: 2026-07-05. **Estado: todas las fases ejecutadas** salvo los ítems que dependen de credenciales/procesos externos o de hitos futuros (marcados con nota en cada casilla).

## Visión

Micro-framework web para Java 21+ con **cero dependencias en el core**, **virtual threads por defecto** y una API expresiva estilo Express. Objetivo 1.0: que un desarrollador construya y despliegue una API REST productiva en menos de 10 minutos, con un jar final < 5 MB.

## Reglas de arquitectura (aplican a todas las fases)

1. **DIP:** la API pública vive en `ligero-core`; motores de servidor, JSON y templates son adapters detrás de SPIs (`ServerEngine`, `BodyMapper`, `TemplateEngine`) resueltos por `ServiceLoader` o inyección explícita.
2. **OCP:** toda funcionalidad transversal (CORS, auth, static files, compresión, métricas) se implementa como `Middleware`, nunca modificando el core.
3. **SRP/DRY:** una sola clase para normalización de paths (`PathNormalizer`), una para matching (`RouteTrie`). Prohibido duplicar esa lógica.
4. **LSP:** los contratos de `HttpRequest`/`HttpResponse`/`Context` no dependen de la implementación concreta (nada de `instanceof` para activar features).
5. **Sin dependencias externas en `ligero-core`.** Jackson solo en `ligero-json`; SLF4J-API es la única excepción permitida.
6. Todo cambio entra con tests; cobertura mínima 80 % en `core`, `http`, `router`.
7. API pública documentada con Javadoc en inglés (docs de usuario en inglés y español).

---

## Fase 0 — Estabilización (v0.2.0) — **bloqueante para cualquier release**

**Objetivo:** que lo que ya existe funcione, compile limpio y sea consumible como dependencia.
**Esfuerzo estimado:** 2–3 semanas · **Prioridad:** crítica

### 0.1 Corrección de bugs (referencias en ARCHITECTURE_ANALYSIS.md §3)

- [x] **B1 — Path params rotos:** eliminar el chequeo `instanceof WrappedHttpRequest` en `Router.handle`; el router construye el request con params y lo pasa al handler (o `Context` mutable en Fase 1). Test de regresión end-to-end: `GET /users/42` debe devolver `id=42` a través del servidor real.
- [x] **B2 — Quitar `--enable-preview`** de compilación, tests y `JavaExec` (no se usa ninguna API preview). Verificar que los `.class` publicados no llevan flag preview.
- [x] **B3 — `redirect()`:** respetar `headersSent`, permitir 301/303/307/308 (`redirect(String url, int status)`), no pisar `status()` sin avisar.
- [x] **B4 — Normalización:** regex correcta (`/{2,}` → `/`) y centralizada en una utilidad única `PathNormalizer` usada por `Ligero`, `Router` y `HttpServer` (elimina la triplicación).
- [x] **B5 — Virtual threads:** `Executors.newVirtualThreadPerTaskExecutor()` como executor por defecto; pool fijo solo como opción explícita.
- [x] **B7 — Headers case-insensitive** (`TreeMap(String.CASE_INSENSITIVE_ORDER)`), calculados una vez y cacheados; exponer multi-valor (`getHeaderValues(name)`).
- [x] **B8 — Límite de body configurable** (default 10 MB → `413 Payload Too Large`) y timeouts de lectura.
- [x] **B9 — Query params sin valor** → `""` en lugar de `null`; soportar claves repetidas (`getQueryParams(name) → List<String>`).

### 0.2 Higiene de dependencias y build

- [x] Eliminar `io.javalin:javalin` de `router` (sin usar; arrastra Jetty completo — contradice "ligero").
- [x] Eliminar `com.sun.net.httpserver:http:20070405` de `http` (la clase viene en el JDK) y la dependencia `http → json`.
- [x] Actualizar Jackson a la última 2.x estable; gestionar versiones con *version catalog* (`gradle/libs.versions.toml`).
- [x] Renombrar artefactos publicados a `ligero-core`, `ligero-http`, `ligero-router`, `ligero-json`, `ligero-server` (en `settings.gradle` o `archivesName`); alinear con el README.
- [x] `examples`: excluir de `maven-publish` y del pipeline de release; eliminar `group/version` hardcodeados en `json/build.gradle` (B11, B12).
- [x] Migrar publicación de OSSRH (`s01.oss.sonatype.org`, retirado) al **Central Portal** de Sonatype (B13): URLs migradas y job de CI de snapshots condicionado a credenciales. *(Único paso restante: dar de alta las credenciales `MAVEN_CENTRAL_*` como secrets — acción del propietario.)*
- [x] Añadir archivo `LICENSE` (Apache 2.0) — el README ya lo enlaza.

### 0.3 Logging

- [x] Sustituir los ~40 `System.out.println` por **SLF4J-API** (niveles `debug`/`trace` para el matching de rutas); `slf4j-simple` solo en `examples` y tests (B6).

### 0.4 Tests y CI

- [x] Tests unitarios: `Router`/`RouteTrie` (matching exacto, params, raíz, backtracking, colisiones, wildcard), `PathNormalizer`, `Context`, middlewares, `Cookie`, validación, config y `Json`; los adapters del engine se cubren con los tests de integración end-to-end.
- [x] Tests de integración: servidor real en puerto efímero + `java.net.http.HttpClient` (GET/POST/PUT/DELETE, 404, 500, redirect, query params, path params, body grande, concurrencia básica).
- [x] Cobertura con JaCoCo, umbral 80 % en `core/http/router`.
- [x] GitHub Actions: build + test + verificación de cobertura en JDK 21 (Linux) en cada PR. Job de snapshots añadido (se activa al configurar los secrets). *(Matriz JDK 25 pendiente: requiere Gradle 9.)*
- [x] README: corregir instalación (versión real, sin badge de Maven Central hasta publicar), documentar la limitación de path params corregida.

**Criterio de salida de fase:** `./gradlew build` verde en CI, ejemplo del README funcionando tal cual está escrito, artefactos `ligero-*` instalables desde un repositorio de snapshots.

---

## Fase 1 — Núcleo extensible y SOLID (v0.3.0)

**Objetivo:** reorganizar la arquitectura para que crecer no exija tocar el core.
**Esfuerzo estimado:** 4–6 semanas · **Prioridad:** alta

### 1.1 Reorganización de módulos (DIP)

- [x] Mover la fachada `Ligero` y la API pública a `ligero-core` (hoy `core` está vacío y `Ligero` vive en `server`).
- [x] Definir SPI `ServerEngine` en `core`; `ligero-server` pasa a ser `ligero-server-jdk`, implementación por defecto descubierta vía `ServiceLoader`.
- [x] Definir SPI `BodyMapper` en `core`; `ligero-json` registra la implementación Jackson. `res.json()`/`req.body(Class)` fallan con mensaje claro si no hay mapper en el classpath.
- [x] `module-info.java` en todos los módulos (JPMS): exportar solo la API, `provides/uses` para las SPIs (los tests se ejecutan en classpath para acceso white-box).

### 1.2 Middleware (OCP — la pieza más importante del framework)

- [x] `interface Middleware { void handle(Context ctx, Next next); }` con cadena componible: `app.use(mw)`, `app.use("/api", mw)`, middleware por ruta.
- [x] Orden determinista, corte de cadena (no llamar `next`), y hooks `before`/`after`.
- [x] Middlewares incluidos de serie: request-logging, request-id.

### 1.3 API de request/response unificada

- [x] Introducir `Context` (envuelve request+respuesta, atributos por-request, `ScopedValue` para acceso implícito). Mantener la firma `(req, res)` como sobrecarga para no romper la API existente.
- [x] Soporte completo de métodos: `PATCH`, `HEAD`, `OPTIONS`, `app.route(method, path, h)`, y `app.any(path, h)`.
- [x] Grupos de rutas: `app.group("/api/v1", g -> { g.get(...); })` (elimina el hack actual del contextPath).
- [x] Wildcards y splat: `/files/*path`; parámetros tipados `ctx.pathParamAsInt("id")` con 400 automático si no parsea.
- [x] Cookies (lectura/escritura, atributos SameSite/HttpOnly/Secure), form data `application/x-www-form-urlencoded` **y `multipart/form-data`** (`ctx.multipart()`), y content negotiation por `Accept` (`ctx.accepts()`, `ctx.preferredType()`).

### 1.4 Manejo de errores (SRP)

- [x] Jerarquía `HttpException(status, message)` + `app.exception(Class<T>, handler)` + `app.error(404, handler)`.
- [x] Por defecto: JSON de error uniforme, **sin stack trace al cliente** (hoy `e.getMessage()` se envía en el 500 — fuga de información), stack trace al log.

### 1.5 Router escalable

- [x] Sustituir la búsqueda lineal O(n) por un **trie de segmentos** (O(longitud del path)); prioridad estático > parámetro > wildcard.
- [x] Benchmark JMH del matching (módulo `benchmarks`, `./gradlew :benchmarks:jmh`): ~140–215 ns/op con 301 rutas registradas.

**Criterio de salida:** un plugin de terceros (p. ej. un middleware CORS externo) puede añadirse sin modificar ni recompilar el core; `examples` migrado a la nueva API.

---

## Fase 2 — Funcionalidad web esencial (v0.4.0)

**Objetivo:** cubrir lo que cualquier app web real necesita, todo como middleware/plugins.
**Esfuerzo estimado:** 4–6 semanas · **Prioridad:** alta

- [x] **Static files:** `app.staticFiles("/static", Location.CLASSPATH | EXTERNAL)`, con ETag, `Cache-Control` y protección path-traversal (tests de seguridad obligatorios).
- [x] **CORS:** middleware configurable (orígenes, métodos, headers, credentials, preflight, `maxAge`).
- [x] **Compresión:** gzip por `Accept-Encoding`, umbral mínimo configurable.
- [x] **Templates:** SPI `TemplateEngine` + `ctx.render("view", model)`; primer adapter `ligero-template-mustache` (JMustache, dependencia mínima) con caché de compilación y escape HTML.
- [x] **Validación:** helpers `ctx.bodyValidator(Class).check(...)` → 400 con detalle de campos.
- [x] **Configuración:** `LigeroConfig` tipado (records) cargable de properties/env-vars con precedencia documentada; `Ligero.create(cfg)`.
- [x] **DI opcional (no obligatorio):** registro simple `app.register(Interface.class, impl)` / `ctx.get(Interface.class)`. Sin reflexión mágica ni classpath scanning — coherente con la filosofía "ligero".
- [x] Graceful shutdown real: drenaje de conexiones en curso con deadline configurable, hook automático opcional (`app.start()` registra shutdown hook).

**Criterio de salida:** se puede construir una web app completa (HTML + formularios + API JSON + assets) solo con módulos `ligero-*`.

---

## Fase 3 — Producción: seguridad y observabilidad (v0.5.0)

**Objetivo:** operable y defendible en producción.
**Esfuerzo estimado:** 5–8 semanas · **Prioridad:** media-alta

### Seguridad
- [x] Middleware de security headers (`X-Content-Type-Options`, `X-Frame-Options`, HSTS, CSP configurable).
- [x] Auth: `ligero-auth` con Basic (en core), Bearer/**JWT HS256** (firma y verificación con `javax.crypto`, sin dependencias; rechaza `alg=none`) y roles (`JwtAuthMiddleware.requireRole(ctx, rol)`).
- [x] CSRF para formularios (middleware, patrón double-submit-cookie sin estado, comparación en tiempo constante).
- [x] Rate limiting simple en memoria (token bucket) como middleware, con SPI para stores externos.
- [x] Sesiones opcionales (cookie firmada HMAC-SHA256; SPI `SessionStore` con implementación en memoria).
- [x] `SECURITY.md` + análisis de dependencias en CI (Dependabot activo + workflow `dependency-review` que falla en severidad alta).

### Observabilidad
- [x] Endpoint `/health` (liveness/readiness) opt-in (`HealthMiddleware` con checks nombrados → 503 con detalle).
- [x] Métricas: contador/latencia por ruta matched (patrón `/users/{id}`, no path crudo) con SPI `MetricsCollector`; colector en memoria de serie y adapter Micrometer (`ligero-metrics-micrometer`).
- [x] Access log estructurado (JSON) opcional (`RequestLoggingMiddleware.json()`).
- [x] Propagación de contexto de trazas (W3C `traceparent`) en el request-id middleware (atributo `traceId`).

### Motor
- [x] Segundo `ServerEngine`: **`ligero-server-jetty`** (Jetty 12 core, gzip vía `GzipHandler`, virtual threads) — la suite de integración corre idéntica sobre ambos engines, validando el DIP end-to-end.
- [x] **SSE** en core (`ctx.sse()`) y **WebSockets**: API en core (`app.websocket(path, WsHandler)`, `WsSession`) con implementación en el adapter Jetty; el engine JDK falla en arranque con mensaje claro si hay rutas WS (no soporta upgrade de protocolo).

**Criterio de salida:** app de referencia desplegada con métricas, health checks y auth; sin hallazgos críticos en un análisis de seguridad básico.

---

## Fase 4 — Ecosistema y camino a 1.0 (v0.6 → v0.9 → v1.0.0)

**Objetivo:** experiencia de desarrollador, comunidad y congelación de API.
**Esfuerzo estimado:** continuo · **Prioridad:** media

### Developer experience
- [x] `ligero-test`: `LigeroTest.create(app -> ...).get("/users/1").execute()` — servidor real en puerto efímero + respuestas assertables (módulo `testkit`).
- [x] OpenAPI: generación 3.0.3 desde rutas registradas (paths, métodos, path params) + Swagger UI opt-in (`ligero-openapi`, `OpenApi.of(app, ...).withSwaggerUi("/docs")`). *(Refinamiento de schemas por anotaciones: fase posterior.)*
- [x] CLI de scaffolding (`ligero new`, `ligero generate controller`): **implementado completo con tests y CI** en la rama `standalone/ligero-cli` de este repo, listo para volcarse al repositorio `ligero-framework/ligero-cli` (crear el repo vacío y hacer `git push <url> standalone/ligero-cli:main`). Verificado end-to-end: el proyecto generado compila y su test e2e pasa contra los artefactos de mavenLocal.
- [x] Hot-reload en modo dev vía build continuo (`./gradlew -t :examples:run`, documentado en CONTRIBUTING). *(Agente de recarga en caliente: no planificado — el arranque es <1 s.)*
- [ ] Arquetipos/templates de proyecto (Gradle y Maven).

### Calidad y rendimiento
- [x] Suite JMH permanente (módulo `benchmarks`: routing con 301 rutas). *(Seguimiento automático de regresiones en CI: pendiente — correr JMH en runners compartidos da resultados ruidosos.)*
- [ ] Participar en TechEmpower benchmarks — **proceso externo** (PR al repo de TechEmpower); no ejecutable desde este repositorio.
- [x] Compatibilidad GraalVM native-image: metadata de reachability añadida (recursos `META-INF/services` y `ligero.properties`); el core no usa reflexión. *(Verificación con toolchain GraalVM real: pendiente de entorno.)*
- [ ] Verificación de compatibilidad binaria entre releases (`japicmp`) — **aplica a partir de 0.9** (aún no existe release publicada contra la que comparar).

### Gobernanza y release 1.0
- [x] Política semver estricta + `CHANGELOG.md` (Keep a Changelog). *(Notas de release automatizadas: al primer release.)*
- [ ] 0.9.x = **API freeze**: solo bugfixes y docs; RFC público para cambios de API — **decisión de proceso al llegar a 0.9**, no ejecutable hoy.
- [x] Documentación completa (Docusaurus, repositorio separado): 16 páginas — intro, instalación, quickstart y guías de routing, context, middleware, errores, configuración, seguridad, observabilidad, tiempo real, templates, OpenAPI, testing y engines, más referencia de módulos y arquitectura. En la rama `standalone/ligero-docs` (build de Docusaurus verificado), lista para `ligero-framework/ligero-docs` con deploy automático a GitHub Pages. *(Pendiente: traducción ES vía i18n y retirar `docs/website` de este repo al migrar.)*
- [x] `CONTRIBUTING.md` (con las reglas de arquitectura), plantillas de issue/PR, código de conducta.
- [ ] **1.0.0:** garantía de compatibilidad, política de soporte, publicación estable en Maven Central — **requiere credenciales del propietario y madurez de API (post 0.9)**.

---

## Explícitamente fuera de alcance (para proteger el "ligero")

- ORM/persistencia propia (documentar integración con JDBC/jOOQ/JDBI en guías, no en código).
- Contenedor DI con classpath scanning, proxies o AOP por bytecode.
- Compatibilidad con Jakarta EE / servlets como API pública.
- Programación reactiva (los virtual threads son la respuesta de Ligero a ese problema).

## Resumen de secuencia

| Fase | Versión | Tema | Duración est. |
|------|---------|------|----------------|
| 0 | 0.2.0 | Estabilización: bugs, deps, tests, CI | 2–3 semanas |
| 1 | 0.3.0 | Middleware, SPIs, Context, trie router | 4–6 semanas |
| 2 | 0.4.0 | Static, CORS, templates, config, validación | 4–6 semanas |
| 3 | 0.5.0 | Seguridad, observabilidad, 2º engine, WS | 5–8 semanas |
| 4 | 0.6–1.0 | DX, OpenAPI, benchmarks, API freeze | continuo |

**Regla de oro:** ninguna feature nueva entra mientras haya bugs abiertos de la fase anterior en esa área.
