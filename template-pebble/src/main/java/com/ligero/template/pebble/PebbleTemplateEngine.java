package com.ligero.template.pebble;

import com.ligero.spi.TemplateEngine;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * {@link TemplateEngine} adapter backed by Pebble (Twig/Jinja-style syntax,
 * autoescaping on). Templates load from the classpath under
 * {@code templates/<name>.peb}.
 */
public final class PebbleTemplateEngine implements TemplateEngine {

    private final PebbleEngine engine;
    private final String prefix;

    public PebbleTemplateEngine() {
        this("templates/");
    }

    public PebbleTemplateEngine(String classpathPrefix) {
        this.prefix = classpathPrefix.endsWith("/") ? classpathPrefix : classpathPrefix + "/";
        // Classpath only: Pebble's default loader also falls back to the file
        // system, which would turn a user-influenced template name into a file read.
        this.engine = new PebbleEngine.Builder()
            .loader(new io.pebbletemplates.pebble.loader.ClasspathLoader())
            .build();
    }

    @Override
    public String render(String templateName, Map<String, Object> model) {
        PebbleTemplate template = engine.getTemplate(prefix + templateName + ".peb");
        StringWriter out = new StringWriter();
        try {
            template.evaluate(out, model);
        } catch (IOException e) {
            throw new UncheckedIOException("Template rendering failed: " + templateName, e);
        }
        return out.toString();
    }
}
