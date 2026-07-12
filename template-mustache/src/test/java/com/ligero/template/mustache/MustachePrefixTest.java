package com.ligero.template.mustache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The custom-prefix constructor normalizes a prefix without a trailing slash. */
class MustachePrefixTest {

    @Test
    void customPrefixWithoutTrailingSlashStillResolves() {
        MustacheTemplateEngine engine = new MustacheTemplateEngine("templates");
        assertThat(engine.render("greeting", Map.of("name", "Ada", "count", 1)))
            .isEqualTo("Hello Ada! You have 1 messages.");
    }
}
