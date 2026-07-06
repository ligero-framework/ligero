/** Ligero JDK server engine: {@code ServerEngine} over com.sun.net.httpserver. */
module com.ligero.server.jdk {
    requires com.ligero.core;
    requires jdk.httpserver;
    requires org.slf4j;

    provides com.ligero.spi.ServerEngine with com.ligero.server.JdkServerEngine;
}
