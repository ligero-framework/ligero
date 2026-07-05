package com.ligero.cli;

/** Code templates emitted by the scaffolder. */
final class Templates {

    private Templates() {
    }

    static final String LIGERO_VERSION = "0.2.0-SNAPSHOT";

    static String settingsGradle(String name) {
        return "rootProject.name = '" + name + "'\n";
    }

    static String buildGradle(String basePackage) {
        return """
            plugins {
                id 'application'
            }

            java {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }

            repositories {
                mavenLocal()
                mavenCentral()
            }

            application {
                mainClass = '%s.Application'
            }

            dependencies {
                implementation 'com.ligero:ligero-core:%s'
                runtimeOnly 'com.ligero:ligero-server-jdk:%s'
                runtimeOnly 'com.ligero:ligero-json:%s'
                runtimeOnly 'org.slf4j:slf4j-simple:2.0.16'

                testImplementation 'com.ligero:ligero-test:%s'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
            }

            test {
                useJUnitPlatform()
            }
            """.formatted(basePackage, LIGERO_VERSION, LIGERO_VERSION, LIGERO_VERSION, LIGERO_VERSION);
    }

    static String gitignore() {
        return """
            build/
            .gradle/
            *.class
            .idea/
            *.iml
            """;
    }

    static String projectReadme(String name) {
        return """
            # %s

            A [Ligero](https://github.com/ligero-framework/ligero) application.

            ```bash
            gradle run    # http://localhost:8080
            gradle test
            ```
            """.formatted(name);
    }

    static String application(String basePackage) {
        return """
            package %s;

            import com.ligero.Ligero;
            import com.ligero.middleware.RequestLoggingMiddleware;

            import java.util.Map;

            public class Application {

                public static void main(String[] args) throws Exception {
                    Ligero app = create();
                    app.start();
                    Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
                    System.out.println("Running at http://localhost:" + app.port());
                }

                /** App wiring, separated from main() so tests can start it on an ephemeral port. */
                public static Ligero create() {
                    Ligero app = Ligero.create(8080);
                    app.use(new RequestLoggingMiddleware());

                    app.get("/", ctx -> ctx.text("It works!"));
                    app.get("/hello/{name}", ctx ->
                        ctx.json(Map.of("hello", ctx.pathParam("name"))));

                    return app;
                }
            }
            """.formatted(basePackage);
    }

    static String applicationTest(String basePackage) {
        return """
            package %s;

            import com.ligero.test.LigeroTest;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            class ApplicationTest {

                @Test
                void helloEndpointResponds() {
                    try (LigeroTest test = LigeroTest.create(app ->
                            app.get("/hello/{name}", ctx ->
                                ctx.json(java.util.Map.of("hello", ctx.pathParam("name")))))) {
                        LigeroTest.TestResponse response = test.get("/hello/world").execute();
                        assertEquals(200, response.status());
                        assertTrue(response.body().contains("world"));
                    }
                }
            }
            """.formatted(basePackage);
    }

    static String controller(String basePackage, String name) {
        String variable = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return """
            package %s;

            import com.ligero.Ligero;
            import com.ligero.http.NotFoundException;

            import java.util.List;
            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.concurrent.atomic.AtomicLong;

            /** CRUD controller for %s resources. */
            public class %sController {

                public record %s(Long id, String name) {
                }

                private final Map<Long, %s> store = new ConcurrentHashMap<>();
                private final AtomicLong ids = new AtomicLong();

                /** Attaches this controller's routes to the app. */
                public void register(Ligero app) {
                    app.group("/api/%ss", api -> {
                        api.get("", ctx -> ctx.json(List.copyOf(store.values())));

                        api.get("/{id}", ctx -> {
                            %s found = store.get(ctx.pathParamAsLong("id"));
                            if (found == null) {
                                throw new NotFoundException("%s not found");
                            }
                            ctx.json(found);
                        });

                        api.post("", ctx -> {
                            %s body = ctx.bodyValidator(%s.class)
                                .check(v -> v.name() != null && !v.name().isBlank(), "name is required")
                                .get();
                            long id = ids.incrementAndGet();
                            %s created = new %s(id, body.name());
                            store.put(id, created);
                            ctx.status(201).json(created);
                        });

                        api.delete("/{id}", ctx -> {
                            store.remove(ctx.pathParamAsLong("id"));
                            ctx.status(204).res().end();
                        });
                    });
                }
            }
            """.formatted(basePackage, name, name, name, name, variable,
                name, name, name, name, name, name);
    }
}
