package com.ligero.template.freemarker;

import com.ligero.spi.TemplateEngine;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * {@link TemplateEngine} adapter backed by FreeMarker. Templates load from
 * the classpath under {@code templates/<name>.ftl} with HTML auto-escaping
 * enabled.
 */
public final class FreemarkerTemplateEngine implements TemplateEngine {

    private final Configuration configuration;

    public FreemarkerTemplateEngine() {
        this("/templates");
    }

    public FreemarkerTemplateEngine(String classpathPrefix) {
        configuration = new Configuration(Configuration.VERSION_2_3_33);
        configuration.setClassLoaderForTemplateLoading(
            Thread.currentThread().getContextClassLoader(),
            classpathPrefix.startsWith("/") ? classpathPrefix.substring(1) : classpathPrefix);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setOutputFormat(freemarker.core.HTMLOutputFormat.INSTANCE);
    }

    @Override
    public String render(String templateName, Map<String, Object> model) {
        try {
            Template template = configuration.getTemplate(templateName + ".ftl");
            StringWriter out = new StringWriter();
            template.process(model, out);
            return out.toString();
        } catch (IOException e) {
            throw new IllegalArgumentException("Template not found: templates/" + templateName + ".ftl", e);
        } catch (TemplateException e) {
            throw new IllegalStateException("Template rendering failed: " + templateName, e);
        }
    }
}
