package com.ligero.testutil;

import com.ligero.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory response recorder for unit tests. */
public final class FakeResponse implements HttpResponse {

    private int status = 200;
    private String contentType = "text/plain; charset=utf-8";
    private boolean committed;
    private String body;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

    @Override
    public HttpResponse status(int statusCode) {
        requireNotCommitted();
        this.status = statusCode;
        return this;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public HttpResponse header(String name, String value) {
        requireNotCommitted();
        headers.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
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
        this.body = body;
        committed = true;
        return this;
    }

    @Override
    public HttpResponse json(Object object) {
        contentType("application/json");
        return send(String.valueOf(object));
    }

    @Override
    public OutputStream getOutputStream() {
        requireNotCommitted();
        committed = true;
        return stream;
    }

    @Override
    public HttpResponse redirect(String url, int statusCode) {
        requireNotCommitted();
        header("Location", url);
        this.status = statusCode;
        committed = true;
        return this;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void end() {
        committed = true;
    }

    public String body() {
        if (body != null) {
            return body;
        }
        return stream.size() > 0 ? stream.toString(StandardCharsets.UTF_8) : null;
    }

    public String headerValue(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String contentTypeValue() {
        return contentType;
    }

    private void requireNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }
}
