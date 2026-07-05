/** Ligero Jetty 12 server engine. */
module com.ligero.server.jetty {
    requires com.ligero.core;
    requires org.eclipse.jetty.server;
    requires org.slf4j;

    provides com.ligero.spi.ServerEngine with com.ligero.server.jetty.JettyServerEngine;
}
