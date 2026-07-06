package com.ligero.template.pebble;

import com.ligero.spi.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PebbleTemplateEngineTest {

    private final PebbleTemplateEngine engine = new PebbleTemplateEngine();

    @Test
    void rendersAndEscapesHtml() {
        assertThat(engine.render("hello", Map.of("name", "Ada"))).isEqualTo("<h1>Hi Ada!</h1>");
        assertThat(engine.render("hello", Map.of("name", "<b>"))).contains("&lt;b&gt;");
    }

    @Test
    void missingTemplateFails() {
        assertThatThrownBy(() -> engine.render("nope", Map.of()))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void discoverableViaServiceLoader() {
        assertThat(ServiceLoader.load(TemplateEngine.class).stream().map(p -> p.type().getName()))
            .contains(PebbleTemplateEngine.class.getName());
    }
}
