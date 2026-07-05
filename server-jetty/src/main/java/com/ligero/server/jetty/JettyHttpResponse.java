package com.ligero.server.jetty;

import com.ligero.http.HttpResponse;
import com.ligero.spi.EngineConfig;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** {@link HttpResponse} adapter for the Jetty 12 core API. */
final class JettyHttpResponse implements HttpResponse {

    private final Response response;
    private final EngineConfig config;
    private String contentType = "text/plain; charset=utf-8";
    private int statusCode = 200;
    private boolean committed;

    JettyHttpResponse(Response response, EngineConfig config) {
        this.response = response;
        this.config = config;
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
            response.getHeaders().add(name, value);
        } else {
            response.getHeaders().put(name, value);
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
        response.getHeaders().put("Content-Type", contentType);
        response.setStatus(statusCode);
        committed = true;
        try (OutputStream out = Content.Sink.asOutputStream(response)) {
            if (body != null && !body.isEmpty()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
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
        response.getHeaders().put("Content-Type", contentType);
        response.setStatus(statusCode);
        committed = true;
        return Content.Sink.asOutputStream(response);
    }

    @Override
    public HttpResponse redirect(String url, int redirectStatus) {
        if (redirectStatus < 300 || redirectStatus > 399) {
            throw new IllegalArgumentException("Redirect status must be 3xx, got " + redirectStatus);
        }
        requireNotCommitted();
        response.getHeaders().put("Location", url);
        statusCode = redirectStatus;
        response.setStatus(redirectStatus);
        committed = true;
        try (OutputStream out = Content.Sink.asOutputStream(response)) {
            // header-only response
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
        response.setStatus(statusCode);
        committed = true;
        try (OutputStream out = Content.Sink.asOutputStream(response)) {
            // empty body
        } catch (IOException e) {
            throw new UncheckedIOException("Could not finalize response", e);
        }
    }

    private void requireNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }
}
