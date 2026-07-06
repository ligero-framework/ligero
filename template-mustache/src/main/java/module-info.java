/** Ligero template engine adapter backed by JMustache. */
module com.ligero.template.mustache {
    requires com.ligero.core;
    requires com.samskivert.jmustache;

    exports com.ligero.template.mustache;

    provides com.ligero.spi.TemplateEngine with com.ligero.template.mustache.MustacheTemplateEngine;
}
