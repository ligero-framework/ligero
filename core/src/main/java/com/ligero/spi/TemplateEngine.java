package com.ligero.spi;

import java.util.Map;

/**
 * SPI for template rendering used by {@code Context.render}. Adapters
 * (e.g. {@code ligero-template-mustache}) register implementations via
 * {@link java.util.ServiceLoader}.
 */
public interface TemplateEngine {

    /**
     * Renders the named template with the given model and returns the
     * resulting markup.
     */
    String render(String templateName, Map<String, Object> model);
}
