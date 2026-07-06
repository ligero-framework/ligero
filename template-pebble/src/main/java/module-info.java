/** Ligero template engine adapter backed by Pebble. */
module com.ligero.template.pebble {
    requires com.ligero.core;
    requires io.pebbletemplates;

    exports com.ligero.template.pebble;

    provides com.ligero.spi.TemplateEngine with com.ligero.template.pebble.PebbleTemplateEngine;
}
