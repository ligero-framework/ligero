package com.ligero.template.freemarker;

import com.ligero.spi.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreemarkerTemplateEngineTest {

    private final FreemarkerTemplateEngine engine = new FreemarkerTemplateEngine();

    @Test
    void rendersAndEscapesHtml() {
        assertThat(engine.render("hello", Map.of("name", "Ada"))).isEqualTo("<h1>Hi Ada!</h1>");
        assertThat(engine.render("hello", Map.of("name", "<b>"))).contains("&lt;b&gt;");
    }

    @Test
    void missingTemplateFails() {
        assertThatThrownBy(() -> engine.render("nope", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoverableViaServiceLoader() {
        assertThat(ServiceLoader.load(TemplateEngine.class).stream().map(p -> p.type().getName()))
            .contains(FreemarkerTemplateEngine.class.getName());
    }
}
