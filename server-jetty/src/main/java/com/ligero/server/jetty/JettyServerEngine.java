package com.ligero.server.jetty;

import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;
import com.ligero.spi.ServerEngine;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * {@link ServerEngine} implementation on Jetty 12 (core API, no servlets).
 * Exists to prove the engine SPI: applications switch engines by swapping
 * the runtime dependency, with zero code changes. Also serves requests on
 * virtual threads when enabled.
 */
public final class JettyServerEngine implements ServerEngine {

    private static final Logger log = LoggerFactory.getLogger(JettyServerEngine.class);

    private Server server;
    private ServerConnector connector;

    @Override
    public void start(EngineConfig config, HttpHandler rootHandler) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Engine already started");
        }
        QueuedThreadPool threadPool = new QueuedThreadPool();
        if (config.virtualThreads()) {
            threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        }
        server = new Server(threadPool);
        connector = new ServerConnector(server);
        connector.setHost(config.host());
        connector.setPort(config.port());
        server.addConnector(connector);

        Handler handler = new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                JettyHttpRequest ligeroRequest = new JettyHttpRequest(request, config.maxBodyBytes());
                JettyHttpResponse ligeroResponse = new JettyHttpResponse(response, config);
                try {
                    rootHandler.handle(ligeroRequest, ligeroResponse);
                } catch (Exception e) {
                    log.error("Request pipeline failed", e);
                    if (!ligeroResponse.isCommitted()) {
                        ligeroResponse.status(500)
                            .contentType("text/plain; charset=utf-8")
                            .send("Internal server error");
                    }
                } finally {
                    ligeroResponse.end();
                    callback.succeeded();
                }
                return true;
            }
        };
        if (config.gzip()) {
            GzipHandler gzip = new GzipHandler();
            gzip.setMinGzipSize(config.gzipMinBytes());
            gzip.setHandler(handler);
            server.setHandler(gzip);
        } else {
            server.setHandler(handler);
        }

        try {
            server.start();
        } catch (Exception e) {
            throw new IOException("Could not start Jetty", e);
        }
        log.debug("Jetty engine listening on {}:{}", config.host(), port());
    }

    @Override
    public void stop(Duration grace) {
        if (server == null) {
            return;
        }
        try {
            server.setStopTimeout(grace.toMillis());
            server.stop();
        } catch (Exception e) {
            log.warn("Error stopping Jetty", e);
        } finally {
            server = null;
            connector = null;
        }
    }

    @Override
    public int port() {
        if (connector == null) {
            throw new IllegalStateException("Engine is not running");
        }
        return connector.getLocalPort();
    }
}
