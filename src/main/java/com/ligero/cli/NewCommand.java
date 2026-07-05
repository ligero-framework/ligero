package com.ligero.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code ligero new <name>}: generates a ready-to-run Gradle project. */
final class NewCommand {

    int run(Path workingDir, List<String> args) throws IOException {
        if (args.isEmpty() || args.get(0).startsWith("--")) {
            throw new IllegalArgumentException("Usage: ligero new <project-name> [--package <base.package>]");
        }
        String name = args.get(0);
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid project name: " + name);
        }
        String basePackage = LigeroCli.option(args, "--package");
        if (basePackage == null) {
            basePackage = "com.example." + name.toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        if (!basePackage.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) {
            throw new IllegalArgumentException("Invalid package name: " + basePackage);
        }

        Path root = workingDir.resolve(name);
        if (Files.exists(root)) {
            throw new IllegalArgumentException("Directory already exists: " + root);
        }
        Path packageDir = Path.of("src/main/java", basePackage.split("\\."));
        Path testPackageDir = Path.of("src/test/java", basePackage.split("\\."));

        write(root.resolve("settings.gradle"), Templates.settingsGradle(name));
        write(root.resolve("build.gradle"), Templates.buildGradle(basePackage));
        write(root.resolve(".gitignore"), Templates.gitignore());
        write(root.resolve("README.md"), Templates.projectReadme(name));
        write(root.resolve(packageDir).resolve("Application.java"), Templates.application(basePackage));
        write(root.resolve(testPackageDir).resolve("ApplicationTest.java"), Templates.applicationTest(basePackage));

        System.out.println("""
            Created project '%s'

            Next steps:
              cd %s
              gradle run          # start on http://localhost:8080
              gradle test         # run the end-to-end test
            """.formatted(name, name));
        return 0;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        System.out.println("  create " + file);
    }
}
