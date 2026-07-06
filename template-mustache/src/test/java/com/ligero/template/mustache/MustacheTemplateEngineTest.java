package com.ligero.template.mustache;

import com.ligero.spi.TemplateEngine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MustacheTemplateEngineTest {

    private final MustacheTemplateEngine engine = new MustacheTemplateEngine();

    @Test
    void rendersTemplateWithModel() {
        String html = engine.render("greeting", Map.of("name", "Ada", "count", 3));
        assertThat(html).isEqualTo("Hello Ada! You have 3 messages.");
    }

    @Test
    void escapesHtmlByDefault() {
        String html = engine.render("greeting", Map.of("name", "<script>", "count", 0));
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void missingTemplateFailsWithClearMessage() {
        assertThatThrownBy(() -> engine.render("nope", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("templates/nope.mustache");
    }

    @Test
    void isDiscoverableViaServiceLoader() {
        assertThat(ServiceLoader.load(TemplateEngine.class).stream().map(p -> p.type().getName()))
            .contains(MustacheTemplateEngine.class.getName());
    }
}
