/** Ligero Jetty 12 server engine. */
module com.ligero.server.jetty {
    requires com.ligero.core;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.websocket.server;
    requires org.eclipse.jetty.websocket.api;
    requires org.slf4j;

    // Jetty introspects the WS listener with MethodHandles at runtime
    opens com.ligero.server.jetty to org.eclipse.jetty.websocket.common;

    provides com.ligero.spi.ServerEngine with com.ligero.server.jetty.JettyServerEngine;
}
