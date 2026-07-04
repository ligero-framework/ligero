package com.ligero.server;

import com.ligero.http.HttpRequest;
import com.ligero.http.PayloadTooLargeException;

import com.sun.net.httpserver.HttpExchange;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link HttpRequest} adapter for {@code com.sun.net.httpserver}. Headers are
 * case-insensitive and computed once; the body stream enforces the configured
 * size limit.
 */
final class JdkHttpRequest implements HttpRequest {

    private final HttpExchange exchange;
    private final long maxBodyBytes;
    private Map<String, List<String>> headers;
    private Map<String, List<String>> queryParams;
    private String cachedBody;

    JdkHttpRequest(HttpExchange exchange, long maxBodyBytes) {
        this.exchange = exchange;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public String getMethod() {
        return exchange.getRequestMethod().toUpperCase();
    }

    @Override
    public String getUri() {
        return exchange.getRequestURI().toString();
    }

    @Override
    public String getProtocol() {
        return exchange.getProtocol();
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        if (headers == null) {
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) ->
                map.put(name, List.copyOf(values)));
            headers = Collections.unmodifiableMap(map);
        }
        return headers;
    }

    @Override
    public Map<String, List<String>> getQueryParams() {
        if (queryParams == null) {
            queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
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
            // a parameter without value maps to "" (never null)
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), k -> new ArrayList<>())
                  .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        params.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(params);
    }

    @Override
    public InputStream getBody() {
        long declared = exchange.getRequestHeaders().getFirst("Content-Length") != null
            ? parseLongSafe(exchange.getRequestHeaders().getFirst("Content-Length"))
            : -1;
        if (declared > maxBodyBytes) {
            throw new PayloadTooLargeException(maxBodyBytes);
        }
        return new BoundedInputStream(exchange.getRequestBody(), maxBodyBytes);
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
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null
            ? null : remote.getAddress().getHostAddress();
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Guards against unbounded request bodies (DoS protection). */
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
