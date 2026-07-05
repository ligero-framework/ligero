package com.ligero.testutil;

import com.ligero.http.HttpRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory request for unit tests. */
public final class FakeRequest implements HttpRequest {

    private String method = "GET";
    private String uri = "/";
    private String body = "";
    private String remoteAddress = "127.0.0.1";
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> queryParams = new TreeMap<>();

    public static FakeRequest of(String method, String uri) {
        FakeRequest request = new FakeRequest();
        request.method = method;
        request.uri = uri;
        return request;
    }

    public FakeRequest header(String name, String value) {
        headers.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        return this;
    }

    public FakeRequest queryParam(String name, String value) {
        queryParams.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        return this;
    }

    public FakeRequest body(String body) {
        this.body = body;
        return this;
    }

    public FakeRequest remoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getUri() {
        return uri;
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    @Override
    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getBodyAsString() {
        return body;
    }

    @Override
    public String getRemoteAddress() {
        return remoteAddress;
    }
}
