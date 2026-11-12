package com.ligero.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles a sample layered package with the processor attached and checks
 * that (a) the whole thing compiles — proving the generated bindings are
 * valid Java against the real core types — and (b) the generated sources
 * contain the expected explicit {@code bind(...)} wiring.
 */
class LigeroProcessorTest {

    @TempDir
    Path dir;

    @Test
    void generatesExplicitBindingsAndAggregatorThatCompile() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Repo.java", "package demo; public interface Repo {}");
        write(src, "JdbcRepo.java", """
            package demo;
            import com.ligero.beans.stereotype.Repository;
            import javax.sql.DataSource;
            @Repository public class JdbcRepo implements Repo {
                public JdbcRepo(DataSource ds) {}
            }""");
        write(src, "Svc.java", "package demo; public interface Svc {}");
        write(src, "DefaultSvc.java", """
            package demo;
            import com.ligero.beans.stereotype.Service;
            @Service public class DefaultSvc implements Svc {
                public DefaultSvc(Repo repo) {}
            }""");
        write(src, "Ctrl.java", """
            package demo;
            import com.ligero.Ligero;
            import com.ligero.beans.stereotype.Controller;
            @Controller public class Ctrl {
                public Ctrl(Svc svc) {}
                public void register(Ligero app) {}
            }""");
        write(src, "Config.java", """
            package demo;
            import com.ligero.beans.Provides;
            import javax.sql.DataSource;
            public class Config {
                @Provides static DataSource ds() { return null; }
            }""");

        Path gen = Files.createDirectories(dir.resolve("generated"));
        Path out = Files.createDirectories(dir.resolve("classes"));

        boolean ok = compileWithProcessor(src, gen, out);
        assertThat(ok).as("sample + generated code compiles").isTrue();

        String module = Files.readString(gen.resolve("demo/LigeroGeneratedModule.java"));
        assertThat(module)
            .contains("implements com.ligero.LigeroModule")
            .contains("builder.bind(javax.sql.DataSource.class, b -> demo.Config.ds());")
            .contains("builder.bind(demo.Repo.class, b -> new demo.JdbcRepo(b.get(javax.sql.DataSource.class)));")
            .contains("builder.bind(demo.Svc.class, b -> new demo.DefaultSvc(b.get(demo.Repo.class)));")
            .contains("builder.bind(demo.Ctrl.class, b -> new demo.Ctrl(b.get(demo.Svc.class)));")
            .contains("beans.get(demo.Ctrl.class).register(app);");

        String aggregator = Files.readString(gen.resolve("com/ligero/generated/GeneratedModules.java"));
        assertThat(aggregator)
            .contains("public static com.ligero.LigeroModule[] all()")
            .contains("new demo.LigeroGeneratedModule()");
    }

    @Test
    void ambiguousConstructorIsACompileError() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Two.java", """
            package demo;
            import com.ligero.beans.stereotype.Service;
            @Service public class Two {
                public Two() {}
                public Two(String a) {}
            }""");
        Path gen = Files.createDirectories(dir.resolve("generated"));
        Path out = Files.createDirectories(dir.resolve("classes"));

        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, gen, out, diagnostics);
        assertThat(ok).isFalse();
        assertThat(diagnostics.toString()).contains("constructors").contains("@Inject");
    }

    @Test
    void componentWithAsKeyAndInjectConstructor() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "A.java", "package demo; public interface A {}");
        write(src, "B.java", "package demo; public interface B {}");
        write(src, "Dep.java", "package demo; public interface Dep {}");
        write(src, "Multi.java", """
            package demo;
            import com.ligero.beans.stereotype.Component;
            import com.ligero.beans.Inject;
            @Component(as = A.class)
            public class Multi implements A, B {
                public Multi() {}
                @Inject public Multi(Dep dep) {}
            }""");
        Path gen = Files.createDirectories(dir.resolve("generated"));
        Path out = Files.createDirectories(dir.resolve("classes"));

        assertThat(compileWithProcessor(src, gen, out)).isTrue();
        // as() picks the key A; @Inject picks the (Dep) constructor
        assertThat(Files.readString(gen.resolve("demo/LigeroGeneratedModule.java")))
            .contains("builder.bind(demo.A.class, b -> new demo.Multi(b.get(demo.Dep.class)));");
    }

    @Test
    void controllerWithoutRegisterMountsNoRoute() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Bare.java", """
            package demo;
            import com.ligero.beans.stereotype.Controller;
            @Controller public class Bare {
                public Bare() {}
            }""");
        Path gen = Files.createDirectories(dir.resolve("generated"));
        Path out = Files.createDirectories(dir.resolve("classes"));

        assertThat(compileWithProcessor(src, gen, out)).isTrue();
        String module = Files.readString(gen.resolve("demo/LigeroGeneratedModule.java"));
        assertThat(module).contains("builder.bind(demo.Bare.class, b -> new demo.Bare());");
        // no register(Ligero) -> routes() body stays empty
        assertThat(module).doesNotContain(".register(app);");
    }

    @Test
    void typedRoutesGenerateARoutesClassThatCompiles() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Color.java", "package demo; public enum Color { RED, GREEN }");
        write(src, "Item.java", "package demo; public record Item(long id, String name) {}");
        write(src, "Items.java", """
            package demo;
            import com.ligero.beans.stereotype.Controller;
            import com.ligero.http.Context;
            import com.ligero.http.Response;
            import com.ligero.web.*;
            import java.util.List;
            import java.util.Optional;
            import java.util.UUID;
            @Controller("/api/items")
            public class Items {
                @Get
                List<Item> list(@QueryParam(defaultValue = "1") int page, @QueryParam String q,
                                @QueryParam("tag") List<String> tags, @QueryParam Color color) { return List.of(); }
                @Get("/{id}")
                Optional<Item> get(@PathParam long id, @HeaderParam("X-Tenant") String tenant) { return Optional.empty(); }
                @Get("/by-key/{key}")
                Item byKey(@PathParam("key") UUID uuid, Context ctx) { return null; }
                @Post @Status(201)
                Response<Item> create(@Body Item item) { return Response.created(item); }
                @Put("/{id}/raw")
                String raw(@PathParam long id, @Body String text) { return text; }
                @Delete("/{id}")
                void delete(@PathParam long id) {}
                @Route(method = "query", path = "/search")
                List<Item> search(@Body Item example) { return List.of(); }
                @Get("/files/*rest")
                String file(@PathParam String rest) { return rest; }
            }""");
        Path gen = freshDir("generated");
        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, gen, freshDir("classes"), diagnostics);
        assertThat(ok).as(diagnostics.toString()).isTrue();

        String routes = Files.readString(gen.resolve("demo/ItemsRoutes.java"));
        assertThat(routes)
            .contains("public static void mount(com.ligero.Ligero app, demo.Items controller)")
            .contains("app.route(\"GET\", \"/api/items\", ctx -> {")
            .contains("com.ligero.web.Binding.toInt(com.ligero.web.Binding.orDefault(ctx.queryParam(\"page\"), \"1\"), \"query parameter\", \"page\")")
            .contains("ctx.queryParams(\"tag\")")
            .contains("com.ligero.web.Binding.toEnum(demo.Color.values(), ctx.queryParam(\"color\"), \"query parameter\", \"color\")")
            .contains("app.route(\"GET\", \"/api/items/{id}\", ctx -> {")
            .contains("com.ligero.web.Binding.toLong(com.ligero.web.Binding.required(ctx.pathParam(\"id\"), \"path parameter\", \"id\"), \"path parameter\", \"id\")")
            .contains("ctx.header(\"X-Tenant\")")
            .contains("com.ligero.web.Binding.toUuid(")
            .contains("app.route(\"POST\", \"/api/items\", ctx -> {")
            .contains("ctx.body(demo.Item.class)")
            .contains("ctx.status(201);")
            .contains("ctx.bodyAsString()")
            .contains("app.route(\"DELETE\", \"/api/items/{id}\", ctx -> {")
            .contains("ctx.respond(null);")
            .contains("app.route(\"QUERY\", \"/api/items/search\", ctx -> {")
            .contains("app.route(\"GET\", \"/api/items/files/*rest\", ctx -> {");

        String module = Files.readString(gen.resolve("demo/LigeroGeneratedModule.java"));
        assertThat(module)
            .contains("builder.bind(demo.Items.class, b -> new demo.Items());")
            .contains("demo.ItemsRoutes.mount(app, beans.get(demo.Items.class));");
    }

    @Test
    void validBodyRequiresAValidatorAtMountTime() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "In.java", "package demo; public record In(String name) {}");
        write(src, "C.java", """
            package demo;
            import com.ligero.beans.stereotype.Controller;
            import com.ligero.web.*;
            @Controller public class C {
                @Post("/in") In create(@Body @Valid In in) { return in; }
            }""");
        Path gen = freshDir("generated");
        assertThat(compileWithProcessor(src, gen, freshDir("classes"))).isTrue();
        assertThat(Files.readString(gen.resolve("demo/CRoutes.java")))
            .contains("com.ligero.web.Binding.requireValidator();")
            .contains("com.ligero.web.Binding.valid(ctx.body(demo.In.class))")
            .contains("app.route(\"POST\", \"/in\", ctx -> {");
    }

    @Test
    void typedControllerImplementingAnInterfaceIsStillBoundAsItself() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Marker.java", "package demo; public interface Marker {}");
        write(src, "C.java", """
            package demo;
            import com.ligero.beans.stereotype.Controller;
            import com.ligero.web.Get;
            @Controller("/c") public class C implements Marker {
                @Get String hi() { return "hi"; }
            }""");
        Path gen = freshDir("generated");
        assertThat(compileWithProcessor(src, gen, freshDir("classes"))).isTrue();
        assertThat(Files.readString(gen.resolve("demo/LigeroGeneratedModule.java")))
            .contains("builder.bind(demo.C.class, b -> new demo.C());")
            .contains("demo.CRoutes.mount(app, beans.get(demo.C.class));");
    }

    @Test
    void typedRouteMistakesAreCompileErrors() throws IOException {
        assertRouteError("""
            @Controller("/a") public class C {
                @Get("/{id}") String get(@PathParam long userId) { return ""; }
            }""", "has no {userId} segment");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") String get(String mystery) { return ""; }
            }""", "say where this parameter comes from");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") String get(@QueryParam java.time.Instant when) { return ""; }
            }""", "unsupported parameter type");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") private String get() { return ""; }
            }""", "non-private instance methods");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") @Post("/x") String both() { return ""; }
            }""", "more than one HTTP method annotation");
        assertRouteError("""
            @Controller public class C {
                @Post("/x") String post(@Body java.util.List<String> names) { return ""; }
            }""", "@Body needs a concrete class or record");
        assertRouteError("""
            @Controller public class C {
                @Post("/x") String post(@Body String a, @Body String b) { return ""; }
            }""", "single @Body");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") String get(@QueryParam @Valid String q) { return ""; }
            }""", "@Valid applies to the @Body parameter");
        assertRouteError("""
            public class C {
                @Get("/x") String get() { return ""; }
            }""", "only works inside a class annotated with @Controller");
        assertRouteError("""
            @Controller public class C {
                @Get("/x") @Status(42) String get() { return ""; }
            }""", "valid HTTP status code");
    }

    private void assertRouteError(String classBody, String expectedMessage) throws IOException {
        Path root = Files.createTempDirectory(dir, "case");
        Path src = Files.createDirectories(root.resolve("src/demo"));
        write(src, "C.java", "package demo;\nimport com.ligero.beans.stereotype.Controller;\n"
            + "import com.ligero.web.*;\n" + classBody);
        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, Files.createDirectories(root.resolve("generated")),
            Files.createDirectories(root.resolve("classes")), diagnostics);
        assertThat(ok).as("expected a compile error containing: " + expectedMessage).isFalse();
        assertThat(diagnostics.toString()).contains(expectedMessage);
    }

    @Test
    void providesMustBeStatic() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "Config.java", """
            package demo;
            import com.ligero.beans.Provides;
            import javax.sql.DataSource;
            public class Config {
                @Provides DataSource ds() { return null; }
            }""");
        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, freshDir("generated"), freshDir("classes"), diagnostics);
        assertThat(ok).isFalse();
        assertThat(diagnostics.toString()).contains("static");
    }

    @Test
    void abstractClassIsRejected() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "AbstractSvc.java", """
            package demo;
            import com.ligero.beans.stereotype.Service;
            @Service public abstract class AbstractSvc {}""");
        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, freshDir("generated"), freshDir("classes"), diagnostics);
        assertThat(ok).isFalse();
        assertThat(diagnostics.toString()).contains("concrete class");
    }

    @Test
    void nonInjectableConstructorParamIsRejected() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        write(src, "NeedsInt.java", """
            package demo;
            import com.ligero.beans.stereotype.Service;
            @Service public class NeedsInt {
                public NeedsInt(int port) {}
            }""");
        StringWriter diagnostics = new StringWriter();
        boolean ok = compileWithProcessor(src, freshDir("generated"), freshDir("classes"), diagnostics);
        assertThat(ok).isFalse();
        assertThat(diagnostics.toString()).contains("injectable");
    }

    private Path freshDir(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name));
    }

    private boolean compileWithProcessor(Path srcDir, Path genDir, Path outDir) throws IOException {
        return compileWithProcessor(srcDir, genDir, outDir, new StringWriter());
    }

    private boolean compileWithProcessor(Path srcDir, Path genDir, Path outDir, StringWriter out)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        List<Path> sources;
        try (var stream = Files.list(srcDir)) {
            sources = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
        List<String> options = List.of(
            "-processor", "com.ligero.processor.LigeroProcessor",
            "-classpath", System.getProperty("java.class.path"),
            "-s", genDir.toString(),
            "-d", outDir.toString());
        return compiler.getTask(out, fm, null, options, null, units).call();
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }
}
