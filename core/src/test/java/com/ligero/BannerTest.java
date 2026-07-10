package com.ligero;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BannerTest {

    private static final String ESC = "\u001b";

    static class JdkServerEngine {
    }

    static class JettyServerEngine {
    }

    @Test
    void offDisablesTheBanner() {
        assertThat(Banner.isOff("off")).isTrue();
        assertThat(Banner.isOff("false")).isTrue();
        assertThat(Banner.isOff("OFF")).isTrue();
        assertThat(Banner.isOff(null)).isFalse();
        assertThat(Banner.isOff("color")).isFalse();
    }

    @Test
    void colorModeIsDeterministicForExplicitModes() {
        assertThat(Banner.colorEnabled("plain", true)).isFalse();   // forced off
        assertThat(Banner.colorEnabled("color", false)).isTrue();   // forced on even without a tty
        assertThat(Banner.colorEnabled("always", false)).isTrue();
    }

    @Test
    void renderStripsAnsiWhenColorIsOff() {
        String art = ESC + "[38;2;30;95;214mLIGERO" + ESC + "[0m";
        String plain = Banner.render(art, false);
        assertThat(plain).isEqualTo("LIGERO").doesNotContain(ESC);

        String colored = Banner.render(art, true);
        assertThat(colored).contains(ESC).contains("LIGERO");
    }

    @Test
    void renderSubstitutesTheVersion() {
        assertThat(Banner.render("v=${version}", false)).isEqualTo("v=" + Banner.version());
        assertThat(Banner.version()).isNotBlank();
    }

    @Test
    void startedLineReadsLikeSpringBoot() {
        String line = Banner.startedLine("localhost", 8080, "/",
            new JdkServerEngine(), true, System.nanoTime());
        assertThat(line)
            .contains("Ligero started in")
            .contains("http://localhost:8080")
            .contains("JDK engine")
            .contains("virtual threads");
    }

    @Test
    void startedLineNamesTheEngineAndThreadModel() {
        assertThat(Banner.startedLine("h", 1, "/", new JettyServerEngine(), false, System.nanoTime()))
            .contains("Jetty engine").contains("platform threads");
        assertThat(Banner.startedLine("h", 1, "/ctx", null, true, System.nanoTime()))
            .contains("http://h:1/ctx");
    }

    @Test
    void printEmitsTheBannerArt() {
        // In CI LIGERO_BANNER is unset, so the banner prints (colored or plain).
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Banner.print(new PrintStream(baos, true, StandardCharsets.UTF_8));
        String out = baos.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("the lightweight java web framework");
    }
}
