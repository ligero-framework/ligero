package com.ligero.template.mustache;

import com.ligero.spi.TemplateEngine;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link TemplateEngine} adapter backed by JMustache. Templates are loaded
 * from the classpath under {@code templates/<name>.mustache} and cached
 * after first compilation.
 */
public final class MustacheTemplateEngine implements TemplateEngine {

    private final Mustache.Compiler compiler = Mustache.compiler().escapeHTML(true);
    private final Map<String, Template> cache = new ConcurrentHashMap<>();
    private final String prefix;

    public MustacheTemplateEngine() {
        this("templates/");
    }

    public MustacheTemplateEngine(String resourcePrefix) {
        this.prefix = resourcePrefix.endsWith("/") ? resourcePrefix : resourcePrefix + "/";
    }

    @Override
    public String render(String templateName, Map<String, Object> model) {
        return cache.computeIfAbsent(templateName, this::compile).execute(model);
    }

    private Template compile(String templateName) {
        String resource = prefix + templateName + ".mustache";
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("Template not found on classpath: " + resource);
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return compiler.compile(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read template " + resource, e);
        }
    }
}
