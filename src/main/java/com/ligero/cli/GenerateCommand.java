package com.ligero.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** {@code ligero generate controller <Name>}: adds a controller to an existing project. */
final class GenerateCommand {

    int run(Path workingDir, List<String> args) throws IOException {
        if (args.size() < 2 || !"controller".equals(args.get(0))) {
            throw new IllegalArgumentException("Usage: ligero generate controller <Name> [--package <base.package>]");
        }
        String rawName = args.get(1);
        if (!rawName.matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("Invalid controller name: " + rawName);
        }
        String name = Character.toUpperCase(rawName.charAt(0)) + rawName.substring(1);
        String basePackage = LigeroCli.option(args, "--package");
        if (basePackage == null) {
            basePackage = inferPackage(workingDir);
        }

        Path file = workingDir
            .resolve(Path.of("src/main/java", basePackage.split("\\.")))
            .resolve(name + "Controller.java");
        if (Files.exists(file)) {
            throw new IllegalArgumentException("File already exists: " + file);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, Templates.controller(basePackage, name));
        System.out.println("  create " + file);
        System.out.println("""

            Register it in your Application:
              new %sController().register(app);
            """.formatted(name));
        return 0;
    }

    /** Uses the single top-level package under src/main/java, if unambiguous. */
    private static String inferPackage(Path workingDir) throws IOException {
        Path sourceRoot = workingDir.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException(
                "src/main/java not found — run inside a project or pass --package");
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(p -> p.getFileName().toString().equals("Application.java"))
                .findFirst()
                .map(p -> sourceRoot.relativize(p.getParent()).toString()
                    .replace(java.io.File.separatorChar, '.'))
                .orElseThrow(() -> new IllegalArgumentException(
                    "Could not locate Application.java — pass --package explicitly"));
        }
    }
}
