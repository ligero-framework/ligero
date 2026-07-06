package com.ligero.server.jetty;

import com.ligero.http.HttpRequest;
import com.ligero.http.PayloadTooLargeException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@link HttpRequest} adapter for the Jetty 12 core API. */
final class JettyHttpRequest implements HttpRequest {

    private final Request request;
    private final long maxBodyBytes;
    private Map<String, List<String>> headers;
    private Map<String, List<String>> queryParams;
    private String cachedBody;
    private InputStream bodyStream;

    JettyHttpRequest(Request request, long maxBodyBytes) {
        this.request = request;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public String getMethod() {
        return request.getMethod().toUpperCase();
    }

    @Override
    public String getUri() {
        return request.getHttpURI().getPathQuery();
    }

    @Override
    public String getProtocol() {
        return request.getConnectionMetaData().getProtocol();
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        if (headers == null) {
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            request.getHeaders().forEach(field ->
                map.computeIfAbsent(field.getName(), n -> new ArrayList<>()).add(field.getValue()));
            map.replaceAll((k, v) -> Collections.unmodifiableList(v));
            headers = Collections.unmodifiableMap(map);
        }
        return headers;
    }

    @Override
    public Map<String, List<String>> getQueryParams() {
        if (queryParams == null) {
            queryParams = parseQuery(request.getHttpURI().getQuery());
        }
        return queryParams;
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), k -> new ArrayList<>())
                  .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        params.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(params);
    }

    @Override
    public InputStream getBody() {
        long declared = request.getHeaders().getLongField("Content-Length");
        if (declared > maxBodyBytes) {
            throw new PayloadTooLargeException(maxBodyBytes);
        }
        if (bodyStream == null) {
            bodyStream = new BoundedInputStream(Content.Source.asInputStream(request), maxBodyBytes);
        }
        return bodyStream;
    }

    @Override
    public String getBodyAsString() {
        if (cachedBody == null) {
            try (InputStream in = getBody()) {
                cachedBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read request body", e);
            }
        }
        return cachedBody;
    }

    @Override
    public String getRemoteAddress() {
        return Request.getRemoteAddr(request);
    }

    private final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --remaining < 0) {
                throw new PayloadTooLargeException(maxBodyBytes);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                remaining -= read;
                if (remaining < 0) {
                    throw new PayloadTooLargeException(maxBodyBytes);
                }
            }
            return read;
        }
    }
}
