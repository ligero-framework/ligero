package com.ligero.server;

import com.ligero.http.HttpResponse;
import com.ligero.spi.EngineConfig;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * {@link HttpResponse} adapter for {@code com.sun.net.httpserver}. Tracks the
 * committed state so headers can never be written twice, and optionally
 * gzips bodies when enabled and accepted by the client.
 */
final class JdkHttpResponse implements HttpResponse {

    private final HttpExchange exchange;
    private final EngineConfig config;
    private final boolean clientAcceptsGzip;
    private String contentType = "text/plain; charset=utf-8";
    private int statusCode = 200;
    private boolean committed;

    JdkHttpResponse(HttpExchange exchange, EngineConfig config) {
        this.exchange = exchange;
        this.config = config;
        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        this.clientAcceptsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");
    }

    @Override
    public HttpResponse status(int statusCode) {
        requireNotCommitted();
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public int getStatus() {
        return statusCode;
    }

    @Override
    public HttpResponse header(String name, String value) {
        requireNotCommitted();
        if ("Set-Cookie".equalsIgnoreCase(name) || "Vary".equalsIgnoreCase(name)) {
            exchange.getResponseHeaders().add(name, value);
        } else {
            exchange.getResponseHeaders().set(name, value);
        }
        return this;
    }

    @Override
    public HttpResponse contentType(String contentType) {
        requireNotCommitted();
        this.contentType = contentType;
        return this;
    }

    @Override
    public HttpResponse send(String body) {
        requireNotCommitted();
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (body == null || body.isEmpty()) {
                exchange.sendResponseHeaders(statusCode, -1);
                committed = true;
                return this;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (config.gzip() && clientAcceptsGzip && bytes.length >= config.gzipMinBytes()) {
                bytes = gzip(bytes);
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(statusCode, bytes.length);
            committed = true;
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not send response", e);
        }
        return this;
    }

    @Override
    public HttpResponse json(Object object) {
        if (config.bodyMapper() == null) {
            throw new IllegalStateException(
                "No BodyMapper found. Add ligero-json (or another BodyMapper implementation) to the classpath.");
        }
        contentType("application/json");
        return send(config.bodyMapper().writeJson(object));
    }

    @Override
    public OutputStream getOutputStream() {
        requireNotCommitted();
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, 0); // chunked
            committed = true;
            return exchange.getResponseBody();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open response stream", e);
        }
    }

    @Override
    public HttpResponse redirect(String url, int redirectStatus) {
        if (redirectStatus < 300 || redirectStatus > 399) {
            throw new IllegalArgumentException("Redirect status must be 3xx, got " + redirectStatus);
        }
        requireNotCommitted();
        try {
            exchange.getResponseHeaders().set("Location", url);
            exchange.sendResponseHeaders(redirectStatus, -1);
            statusCode = redirectStatus;
            committed = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not redirect to " + url, e);
        }
        return this;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void end() {
        if (committed) {
            return;
        }
        try {
            exchange.sendResponseHeaders(statusCode, -1);
            committed = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not finalize response", e);
        }
    }

    private void requireNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length / 2 + 16);
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
            out.write(bytes);
        }
        return buffer.toByteArray();
    }
}
