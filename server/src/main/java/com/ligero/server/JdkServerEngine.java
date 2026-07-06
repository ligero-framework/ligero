package com.ligero.server;

import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * {@link ServerEngine} implementation on top of the JDK's built-in
 * {@code com.sun.net.httpserver} — zero external dependencies. Requests are
 * served on virtual threads (Project Loom) by default, so blocking I/O in
 * handlers scales without tuning a pool.
 */
public final class JdkServerEngine implements ServerEngine {

    private static final Logger log = LoggerFactory.getLogger(JdkServerEngine.class);

    private com.sun.net.httpserver.HttpServer server;
    private ExecutorService executor;

    @Override
    public void start(EngineConfig config, HttpHandler rootHandler) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Engine already started");
        }
        if (!config.webSockets().isEmpty()) {
            throw new IllegalStateException(
                "The JDK server engine does not support WebSockets. "
                + "Add ligero-server-jetty to the classpath to serve WebSocket routes.");
        }
        server = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress(config.host(), config.port()), 0);
        executor = config.virtualThreads()
            ? Executors.newVirtualThreadPerTaskExecutor()
            : Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        server.createContext("/", exchange -> {
            try (exchange) {
                JdkHttpRequest request = new JdkHttpRequest(exchange, config.maxBodyBytes());
                JdkHttpResponse response = new JdkHttpResponse(exchange, config);
                try {
                    rootHandler.handle(request, response);
                } catch (Exception e) {
                    // The core pipeline maps exceptions; reaching this point
                    // means the pipeline itself failed. Never leak details.
                    log.error("Request pipeline failed", e);
                    if (!response.isCommitted()) {
                        response.status(500)
                                .contentType("text/plain; charset=utf-8")
                                .send("Internal server error");
                    }
                } finally {
                    response.end();
                }
            }
        });
        server.setExecutor(executor);
        server.start();
        log.debug("JDK server engine listening on {}:{}", config.host(), port());
    }

    @Override
    public void stop(Duration grace) {
        if (server == null) {
            return;
        }
        server.stop((int) Math.min(Integer.MAX_VALUE, grace.toSeconds()));
        server = null;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(grace.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public int port() {
        if (server == null) {
            throw new IllegalStateException("Engine is not running");
        }
        return server.getAddress().getPort();
    }
}
