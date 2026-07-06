/** Ligero template engine adapter backed by FreeMarker. */
module com.ligero.template.freemarker {
    requires com.ligero.core;
    requires freemarker;

    exports com.ligero.template.freemarker;

    provides com.ligero.spi.TemplateEngine with com.ligero.template.freemarker.FreemarkerTemplateEngine;
}
