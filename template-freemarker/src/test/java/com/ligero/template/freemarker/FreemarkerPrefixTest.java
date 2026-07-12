package com.ligero.template.freemarker;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The custom-prefix constructor accepts a classpath prefix without a leading slash. */
class FreemarkerPrefixTest {

    @Test
    void customPrefixWithoutLeadingSlashStillResolves() {
        FreemarkerTemplateEngine engine = new FreemarkerTemplateEngine("templates");
        assertThat(engine.render("hello", Map.of("name", "Ada"))).isEqualTo("<h1>Hi Ada!</h1>");
    }
}
