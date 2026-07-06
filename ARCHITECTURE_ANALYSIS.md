# Análisis de Arquitectura y Viabilidad — Ligero Framework

> **Fecha:** 2026-07-04 · **Versión analizada:** `0.1.0-SNAPSHOT` (commit `bb9417f`)
> Este documento es el diagnóstico técnico que fundamenta la hoja de ruta ([ROADMAP.md](ROADMAP.md)).

## 1. Resumen ejecutivo

Ligero es un micro-framework web para Java 21 con una API fluida inspirada en Express.js. La idea es viable y tiene un nicho real (alternativa ligera a Spring Boot, competencia de Javalin/Spark/Micronaut para APIs pequeñas), pero **el estado actual no es publicable**: hay bugs funcionales en features anunciadas en el README, cero tests, cero CI, dependencias incorrectas y decisiones de build que romperían a cualquier consumidor del artefacto.

**Veredicto de viabilidad:** viable como proyecto, **no viable en su estado actual** como dependencia de terceros. Las fases 0 y 1 del roadmap son condición necesaria para cualquier release público.

## 2. Estado actual

### 2.1 Estructura de módulos

```
ligero/
├── core/      ← VACÍO (solo build.gradle, sin una sola clase)
├── http/      ← Interfaces HttpRequest, HttpResponse, HttpHandler, WrappedHttpRequest
├── router/    ← Router (matching de rutas + dispatch)
├── json/      ← Utilidad Json (wrapper de Jackson)
├── server/    ← HttpServer (adapter de com.sun.net.httpserver) + fachada Ligero
└── examples/  ← ExampleApp
```

**Grafo de dependencias actual (con problemas señalados):**

```
server ──► core (vacío ⚠)
server ──► http, router, json
router ──► http, javalin 5.5.0 (⚠ SIN USAR — arrastra Jetty completo)
http   ──► json (⚠ acoplamiento innecesario), com.sun.net.httpserver:http:20070405 (⚠ artefacto de 2007, la clase ya viene en el JDK)
json   ──► jackson-databind 2.14.2 (⚠ desactualizado, CVEs conocidas)
```

### 2.2 Inventario de código

~1.200 líneas de Java en 11 archivos. Sin tests (`src/test` no existe en ningún módulo). Sin CI (no hay `.github/workflows`). Sin `module-info.java`. Sin archivo `LICENSE` (el README lo enlaza). Documentación mixta español/inglés; `ROUTE.md` y `TO_REVIEW.md` son notas pegadas de conversaciones, no documentación del proyecto.

## 3. Bugs y defectos encontrados (bloqueantes de release)

| # | Severidad | Defecto | Ubicación |
|---|-----------|---------|-----------|
| B1 | **Crítica** | **Los path params `{id}` no funcionan.** `Router.handle` solo inyecta parámetros si `request instanceof WrappedHttpRequest`, pero `HttpServer` pasa `SunHttpRequest` directamente, así que `req.getPathParams()` siempre devuelve el mapa vacío del `default` de la interfaz. La feature estrella del README está rota. | `router/Router.java:228`, `server/HttpServer.java:68` |
| B2 | **Crítica** | `--enable-preview` global en compilación. Los `.class` publicados quedan marcados como preview y **ningún consumidor podría usarlos** sin activar preview en su propia JVM, además de atarse a la versión exacta del JDK. No se usa ninguna API preview en el código. | `build.gradle:44` |
| B3 | Alta | `redirect()` envía headers con 302 fijo sin comprobar `headersSent`, ignora `status()` previo y deja el flag inconsistente; llamado tras un `send()` lanza `IOException` envuelta en `RuntimeException`. | `server/SunHttpResponse.java` |
| B4 | Alta | Regex de normalización incorrecta: `path.replaceAll("//+/", "/")` no colapsa `//` dobles (solo secuencias de 3+). `GET //users` no matchea `/users`. | `router/Router.java:272` |
| B5 | Alta | Pool de hilos fijo (`newFixedThreadPool(nProcs)`) sobre I/O bloqueante: bajo carga, N requests lentas saturan el servidor. En Java 21 lo correcto es `newVirtualThreadPerTaskExecutor()` (Loom), que además es la premisa declarada del proyecto en `ROUTE.md`. | `server/HttpServer.java:49` |
| B6 | Alta | ~40 `System.out.println` de debug en el hot path (cada request imprime ~10 líneas). Contamina stdout del usuario y degrada rendimiento. No hay abstracción de logging. | `Router.java`, `Ligero.java`, `SunHttpRequest.java` |
| B7 | Media | Headers HTTP tratados como case-sensitive (`HashMap` en `getHeaders()`); RFC 9110 exige case-insensitive. Además el mapa se reconstruye en cada llamada. | `server/SunHttpRequest.java` |
| B8 | Media | Sin límite de tamaño de body ni timeouts de lectura → DoS trivial con un body infinito. | `server/SunHttpRequest.java` (`getBodyAsString`) |
| B9 | Media | Query param sin valor inserta `null` en el mapa (`params.put(key, null)`) → NPE latente para el usuario. | `server/SunHttpRequest.java` |
| B10 | Media | `artifactId` publicados serían `core`, `http`, `json`, `router`, `server` (nombres genéricos sin prefijo). El README promete `ligero-core`. Colisión/rechazo garantizado en Maven Central. | `settings.gradle`, `build.gradle` |
| B11 | Media | `examples` genera un fat-jar que re-empaqueta todas las dependencias con `maven-publish` aplicado → publicaría un uber-jar accidentalmente. | `examples/build.gradle` |
| B12 | Baja | `json/build.gradle` re-declara `group`/`version` hardcodeados (`0.1.0` vs `0.1.0-SNAPSHOT` del root) → versiones inconsistentes entre módulos. | `json/build.gradle` |
| B13 | Baja | URL de publicación `s01.oss.sonatype.org` (OSSRH) — servicio retirado en 2025; hay que migrar a Central Portal. | `build.gradle` |
| B14 | Baja | README anuncia badge de Maven Central y versión `0.1.0` que no existen; instrucciones de instalación no funcionales. | `README.md` |

## 4. Evaluación SOLID

### SRP — Responsabilidad Única: ✗
- `Router` mezcla 4 responsabilidades: registro de rutas, algoritmo de matching, normalización de paths y dispatch/manejo de errores. `Route.matches` además muta el mapa de params como efecto lateral.
- `Ligero` (fachada) también normaliza paths y decide política de 404 — lógica duplicada con `Router`.
- La normalización de `contextPath`/paths está **triplicada** (`Ligero`, `HttpServer`, `Router`), con implementaciones ligeramente distintas (violación DRY que ya produjo B4).

### OCP — Abierto/Cerrado: ✗
- No existe ningún punto de extensión: ni middleware, ni plugins, ni SPI. Añadir CORS, auth o logging exige modificar el core.
- Métodos HTTP hardcodeados como métodos separados (`get/post/put/delete`); soportar `PATCH`, `HEAD`, `OPTIONS` requiere tocar `Router` y `Ligero`.

### LSP — Sustitución de Liskov: ✗
- El contrato de `HttpRequest` se rompe según la implementación: los path params solo funcionan con `WrappedHttpRequest` (chequeo `instanceof` en `Router.handle`). Un `HttpRequest` cualquiera no es sustituible — causa directa del bug B1. La interfaz debería exponer los params sin depender del tipo concreto.

### ISP — Segregación de Interfaces: ✓ (aceptable)
- `HttpRequest`, `HttpResponse` y `HttpHandler` son pequeñas y cohesivas. Punto a favor del diseño actual.

### DIP — Inversión de Dependencias: ✗
- `Ligero` instancia `HttpServer` concreto con `new` (acoplado a `com.sun.net.httpserver`). No existe una abstracción `ServerEngine` que permita cambiar de motor (Jetty, Netty, JDK) ni inyectar uno de pruebas.
- La clase pública principal `Ligero` vive en el módulo `server` (capa de infraestructura) mientras `core` está vacío: **la dependencia apunta al revés**. La API debería estar en `core` y `server` ser un detalle enchufable.
- `http` depende de `json`: las abstracciones dependen de un detalle de serialización.

## 5. Escalabilidad

| Aspecto | Estado | Problema |
|---|---|---|
| Concurrencia | ✗ | Thread pool fijo = techo de `nProcs` requests simultáneas con I/O bloqueante (B5). |
| Matching de rutas | ✗ | Búsqueda lineal O(n) por request; con 500 rutas se degrada. Se necesita trie/árbol de segmentos. |
| Memoria por request | ✗ | Reconstrucción de headers en cada `getHeaders()`, prints de debug, splits repetidos de la misma ruta. |
| Backpressure / límites | ✗ | Sin límite de body, sin timeouts, sin límite de conexiones. |
| Observabilidad | ✗ | Sin logging estructurado, sin métricas, sin health checks — inoperable en producción. |
| Escalado horizontal | ✓ | El framework es stateless por diseño; no hay bloqueos aquí. |

## 6. Arquitectura objetivo

Principios: la API en `core`, los detalles en adapters; todo punto de variación detrás de una interfaz con implementación por defecto; cero dependencias externas en `core` (Jackson solo en `ligero-json`, motores de servidor solo en sus adapters).

```
                    ┌────────────────────────────┐
   usuario ───────► │  ligero-core (API pública) │
                    │  App, Router, Middleware,  │
                    │  Context(Req/Res), Config, │
                    │  ExceptionHandler, SPIs    │
                    └─────────────┬──────────────┘
                                  │ SPIs (ServiceLoader / inyección)
        ┌────────────────┬────────┴───────┬───────────────────┐
        ▼                ▼                ▼                   ▼
 ligero-server-jdk  ligero-json     ligero-template-*   ligero-plugins-*
 (com.sun.net,      (Jackson;       (mustache, etc.)    (cors, static,
  virtual threads)   BodyMapper SPI)                     auth, metrics…)
        ▼
 ligero-server-jetty / -netty (futuro, mismo SPI)
```

Decisiones clave:
1. **`ServerEngine` como SPI** (DIP): `core` define `interface ServerEngine { void start(HandlerPipeline p); void stop(Duration grace); }`; el adapter JDK con virtual threads es la implementación por defecto vía `ServiceLoader`.
2. **Middleware como composición de funciones** (OCP): `interface Middleware { void handle(Context ctx, Next next); }` — todo lo transversal (CORS, auth, logging, compresión, static files) se implementa como middleware, nunca en el core.
3. **`Context` único** en lugar de pares `(req, res)`: simplifica firma de handlers y middleware, y da un solo lugar para atributos por-request (con `ScopedValue` de Java 21 para el contexto implícito).
4. **Un único `PathNormalizer` y un `RouteTrie`** en `core`: una sola fuente de verdad para normalización y matching O(longitud-del-path).
5. **`BodyMapper` SPI**: `http`/`core` no conocen Jackson; `ligero-json` registra la implementación.
6. **Errores como jerarquía propia** (`LigeroException`, `HttpException(status)`) + `ExceptionHandler` registrable; nunca `RuntimeException` genéricas ni stack traces al cliente.

## 7. Comparativa de mercado (para enfocar el nicho)

| | Ligero (objetivo) | Javalin | Spark | Spring Boot |
|---|---|---|---|---|
| Dependencias del core | 0 | Jetty | Jetty | decenas |
| Arranque | < 100 ms | ~300 ms | ~300 ms | 2–10 s |
| Virtual threads nativos | ✓ (diferenciador) | opcional | ✗ | opcional |
| Curva de aprendizaje | minutos | minutos | minutos | semanas |

El diferenciador defendible es: **cero dependencias en el core + virtual threads por defecto + API en español/inglés bien documentada**. Sin tests ni estabilidad de API, ninguna de esas ventajas importa: la fase 0 del roadmap es prerequisito de todo lo demás.

## 8. Conclusión

Prioridad absoluta: **corregir antes que crecer**. El detalle accionable, con fases, criterios de aceptación y estimaciones, está en [ROADMAP.md](ROADMAP.md).
