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
