package com.ligero.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LigeroCliTest {

    @TempDir
    Path dir;

    @Test
    void newGeneratesRunnableProject() throws IOException {
        int exit = LigeroCli.run(dir, "new", "my-api", "--package", "com.acme.api");

        assertThat(exit).isZero();
        Path root = dir.resolve("my-api");
        assertThat(root.resolve("settings.gradle")).exists();
        assertThat(Files.readString(root.resolve("build.gradle")))
            .contains("com.ligero:ligero-core")
            .contains("mainClass = 'com.acme.api.Application'");
        assertThat(Files.readString(root.resolve("src/main/java/com/acme/api/Application.java")))
            .contains("package com.acme.api;")
            .contains("Ligero.create(8080)");
        assertThat(root.resolve("src/test/java/com/acme/api/ApplicationTest.java")).exists();
    }

    @Test
    void newDefaultsPackageFromName() throws IOException {
        assertThat(LigeroCli.run(dir, "new", "shop")).isZero();
        assertThat(dir.resolve("shop/src/main/java/com/example/shop/Application.java")).exists();
    }

    @Test
    void newRefusesExistingDirectoryAndBadNames() throws IOException {
        Files.createDirectory(dir.resolve("taken"));
        assertThat(LigeroCli.run(dir, "new", "taken")).isEqualTo(1);
        assertThat(LigeroCli.run(dir, "new", "1bad name")).isEqualTo(1);
        assertThat(LigeroCli.run(dir, "new", "ok", "--package", "Bad.Package")).isEqualTo(1);
    }

    @Test
    void generateControllerInfersPackage() throws IOException {
        LigeroCli.run(dir, "new", "my-api", "--package", "com.acme.api");
        Path project = dir.resolve("my-api");

        int exit = LigeroCli.run(project, "generate", "controller", "user");

        assertThat(exit).isZero();
        Path controller = project.resolve("src/main/java/com/acme/api/UserController.java");
        assertThat(Files.readString(controller))
            .contains("package com.acme.api;")
            .contains("class UserController")
            .contains("app.group(\"/api/users\"");
    }

    @Test
    void generateOutsideProjectFails() {
        assertThat(LigeroCli.run(dir, "generate", "controller", "User")).isEqualTo(1);
    }

    @Test
    void versionAndHelpAndUnknown() {
        assertThat(LigeroCli.run(dir, "version")).isZero();
        assertThat(LigeroCli.run(dir, "help")).isZero();
        assertThat(LigeroCli.run(dir, "wat")).isEqualTo(1);
        assertThat(LigeroCli.run(dir)).isEqualTo(1);
    }
}
