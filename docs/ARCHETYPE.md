# Project archetype

The recommended shape of a Ligero application: **layers inside feature
modules**, with all wiring in one place per feature and none in `main()`.
The runnable reference is [`examples/`](../examples/src/main/java/com/ligero/examples);
`ligero new` generates the same structure.

## Layout

```
com.acme.shop/
├── Application.java          bootstrap only: create(), start(), shutdown hook
├── platform/                 cross-cutting: middleware stack, ops endpoints, config
│   └── Platform.java
├── users/                    ← one package per feature (vertical slice)
│   ├── UsersModule.java      the slice's wiring: beans + routes   (composition)
│   ├── UserController.java   HTTP only: parse, validate, status    (web layer)
│   ├── UserService.java      business contract                     (service layer)
│   ├── DefaultUserService.java
│   ├── UserRepository.java   data-access contract                  (persistence layer)
│   ├── InMemoryUserRepository.java / JdbcUserRepository.java
│   └── User.java             domain record, no framework imports   (domain)
└── orders/ ...
```

Package by **feature**, not by layer: everything that changes together lives
together, and a feature can be deleted by removing one folder and one line in
`Application`.

## Rules

| Layer | May depend on | Must not |
|---|---|---|
| `Application` | modules, `platform` | contain routes, SQL or business rules |
| Controller | service **interface**, domain | touch repositories or SQL; hold business logic |
| Service | repository **interface**, domain | import `com.ligero.http.*` types (other than `HttpException`s for errors) |
| Repository | domain, `DataSource`/JPA | know about HTTP or services |
| Domain | nothing | import the framework |
| Module | everything in its own slice | contain logic — it only wires |

Cross-feature calls go through the other feature's **service interface**,
never its repository.

## Conventions

- **Interfaces between layers** (`UserService`, `UserRepository`); the
  implementation is bound in the module, so tests and devtools can swap or
  spy on it.
- **Controllers expose `register(Ligero app)`**; the module calls it from
  `routes(...)`. Request DTOs are nested records of the controller.
- **Errors are exceptions** (`NotFoundException`, `BadRequestException`, …)
  thrown from the service; the controller never builds error responses.
- **Cross-cutting behaviour is middleware**, installed once in `platform/`.
- **Stereotypes** (`@Controller`, `@Service`, `@Repository`) are metadata that
  colour the devtools graph; they never drive injection.
- **Tests**: one end-to-end test per slice with `ligero-test`, plus plain unit
  tests of services against a fake repository.

## Growing the app

1. New feature → new package with its own `…Module`, add it to `Application.modules()`.
2. New backing store → new `…Repository` implementation, change one line in the module.
3. Shared infrastructure (a `DataSource`) → its own module that other modules
   `b.get(...)`; all modules share one container.
4. When a feature grows large, split it into a Gradle subproject; the module
   boundary is already the seam.

See also the [architecture guide](https://doc.ligeroframework.com/guides/architecture)
and the sibling [`ligero-examples`](https://github.com/ligero-framework/ligero-examples)
(`layered-beans`, `hexagonal-todo`, `commerce-devtools`).
