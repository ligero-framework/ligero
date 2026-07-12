package com.ligero.template.pebble;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The custom-prefix constructor normalizes a prefix without a trailing slash. */
class PebblePrefixTest {

    @Test
    void customPrefixWithoutTrailingSlashStillResolves() {
        PebbleTemplateEngine engine = new PebbleTemplateEngine("templates");
        assertThat(engine.render("hello", Map.of("name", "Ada"))).isEqualTo("<h1>Hi Ada!</h1>");
    }
}
