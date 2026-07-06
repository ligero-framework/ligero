/**
 * Ligero core: public API, router, middleware pipeline and SPIs.
 * Server engines, JSON mappers and template engines plug in through the
 * {@code com.ligero.spi} services.
 */
module com.ligero.core {
    requires transitive org.slf4j;

    exports com.ligero;
    exports com.ligero.config;
    exports com.ligero.http;
    exports com.ligero.middleware;
    exports com.ligero.router;
    exports com.ligero.spi;
    exports com.ligero.validation;
    exports com.ligero.websocket;

    uses com.ligero.spi.ServerEngine;
    uses com.ligero.spi.BodyMapper;
    uses com.ligero.spi.TemplateEngine;
    uses com.ligero.spi.MetricsCollector;
    uses com.ligero.spi.Tracer;
}
