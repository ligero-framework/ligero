/**
 * Ligero Pluma server engine: {@code ServerEngine} implemented from scratch on
 * plain sockets and virtual threads — no third-party code, not even the JDK's
 * built-in HTTP server.
 */
module com.ligero.server.pluma {
    requires com.ligero.core;
    requires org.slf4j;

    exports com.ligero.server.pluma;

    provides com.ligero.spi.ServerEngine with com.ligero.server.pluma.PlumaServerEngine;
}
