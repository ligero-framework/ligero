package com.ligero.spi;

import com.ligero.http.HttpHandler;

import java.io.IOException;
import java.time.Duration;

/**
 * SPI for pluggable HTTP server engines (DIP: the core never instantiates a
 * concrete server). Implementations are discovered via
 * {@link java.util.ServiceLoader} or injected explicitly with
 * {@code Ligero.engine(...)}.
 *
 * <p>The default implementation is {@code ligero-server-jdk}, built on
 * {@code com.sun.net.httpserver} with virtual threads.</p>
 */
public interface ServerEngine {

    /**
     * Binds and starts the server, delegating every request to
     * {@code rootHandler} (the composed middleware + routing pipeline).
     */
    void start(EngineConfig config, HttpHandler rootHandler) throws IOException;

    /**
     * Stops the server gracefully, allowing in-flight requests up to
     * {@code grace} to complete.
     */
    void stop(Duration grace);

    /** Actual bound port (useful when the configured port is 0/ephemeral). */
    int port();
}
