package com.ligero;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Ligero startup banner — the whale plus the wordmark in theme colors,
 * Spring-Boot style. Printed to {@code System.out} when the app starts.
 *
 * <p>Behaviour is controlled by the {@code LIGERO_BANNER} environment variable:
 * <ul>
 *   <li>unset / {@code color} — colored banner (ANSI) when the output looks like
 *       a terminal, otherwise plain;</li>
 *   <li>{@code plain} — always without ANSI colors;</li>
 *   <li>{@code off} / {@code false} — no banner at all.</li>
 * </ul>
 * Colors are also suppressed when {@code NO_COLOR} is set or {@code TERM=dumb}.
 */
final class Banner {

    /** Matches ANSI SGR sequences: ESC [ ... m */
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;]*m");

    private Banner() {
    }

    /** Prints the banner art (respecting {@code LIGERO_BANNER}) to {@code out}. */
    static void print(PrintStream out) {
        String mode = env("LIGERO_BANNER");
        if (isOff(mode)) {
            return;
        }
        String art = render(load(), colorEnabled(mode, System.console() != null));
        if (art != null && !art.isBlank()) {
            out.println(art);
        }
    }

    /** Builds the Spring-style "started" line (logged by {@link Ligero}). */
    static String startedLine(String host, int port, String contextPath,
                              Object engine, boolean virtualThreads, long startNanos) {
        double took = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double jvm = uptimeSeconds();
        String url = "http://" + host + ":" + port
            + ("/".equals(contextPath) ? "" : contextPath);
        return String.format(
            "Ligero started in %.2fs (JVM running for %.2fs) on %s · %s · %s",
            took, jvm, url, engineName(engine),
            virtualThreads ? "virtual threads" : "platform threads");
    }

    // ---- internals (package-private for tests) ----------------------------

    static boolean isOff(String mode) {
        return "off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode);
    }

    static boolean colorEnabled(String mode, boolean tty) {
        if ("plain".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("color".equalsIgnoreCase(mode) || "always".equalsIgnoreCase(mode)) {
            return true;
        }
        if (env("NO_COLOR") != null || "dumb".equalsIgnoreCase(env("TERM"))) {
            return false;
        }
        return tty;
    }

    /** Substitutes the version and strips ANSI when color is off. */
    static String render(String art, boolean color) {
        if (art == null) {
            return null;
        }
        String out = art.replace("${version}", version());
        return color ? out : ANSI.matcher(out).replaceAll("");
    }

    static String version() {
        return Optional.ofNullable(Ligero.class.getPackage().getImplementationVersion())
            .orElse("dev");
    }

    private static String engineName(Object engine) {
        if (engine == null) {
            return "engine";
        }
        String n = engine.getClass().getSimpleName();
        if (n.contains("Jetty")) {
            return "Jetty engine (HTTP/2, WebSockets)";
        }
        if (n.contains("Jdk") || n.contains("JDK")) {
            return "JDK engine";
        }
        return n;
    }

    private static double uptimeSeconds() {
        try {
            return ProcessHandle.current().info().startInstant()
                .map(start -> (System.currentTimeMillis() - start.toEpochMilli()) / 1000.0)
                .orElse(0.0);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static String load() {
        try (InputStream in = Banner.class.getResourceAsStream("/com/ligero/banner.txt")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
    }
}
