package com.ligero.server.loom;

import com.ligero.http.BadRequestException;
import com.ligero.http.HttpRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@link HttpRequest} over a parsed {@link RequestHead}; everything derived is computed lazily. */
final class LoomHttpRequest implements HttpRequest {

    private final Connection connection;
    private final RequestHead head;
    private final long maxBodyBytes;
    private Map<String, List<String>> headers;
    private Map<String, List<String>> queryParams;
    private byte[] body;
    private String bodyText;

    LoomHttpRequest(Connection connection, RequestHead head, long maxBodyBytes) {
        this.connection = connection;
        this.head = head;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public String getMethod() {
        return head.method;
    }

    @Override
    public String getUri() {
        return head.target;
    }

    @Override
    public String getProtocol() {
        return head.http10 ? "HTTP/1.0" : "HTTP/1.1";
    }

    @Override
    public String getHeader(String name) {
        return head.header(name); // no map needed for the common single lookup
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        if (headers == null) {
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < head.headerCount; i++) {
                map.computeIfAbsent(head.names[i], n -> new ArrayList<>(1)).add(head.values[i]);
            }
            map.replaceAll((name, values) -> Collections.unmodifiableList(values));
            headers = Collections.unmodifiableMap(map);
        }
        return headers;
    }

    @Override
    public Map<String, List<String>> getQueryParams() {
        if (queryParams == null) {
            int query = head.target.indexOf('?');
            queryParams = query < 0 ? Map.of() : parseQuery(head.target.substring(query + 1));
        }
        return queryParams;
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery.isEmpty()) {
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
            params.computeIfAbsent(decode(key), k -> new ArrayList<>()).add(decode(value));
        }
        params.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(params);
    }

    private static String decode(String encoded) {
        if (encoded.indexOf('%') < 0 && encoded.indexOf('+') < 0) {
            return encoded;
        }
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // broken percent-encoding is the client's mistake: 400, not 500
            throw new BadRequestException("Malformed percent-encoding in query string");
        }
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(bodyBytes());
    }

    @Override
    public String getBodyAsString() {
        if (bodyText == null) {
            bodyText = new String(bodyBytes(), StandardCharsets.UTF_8);
        }
        return bodyText;
    }

    /** Read from the socket once, then served from memory in any order. */
    private byte[] bodyBytes() {
        if (body == null) {
            body = connection.readBody(maxBodyBytes);
        }
        return body;
    }

    @Override
    public String getRemoteAddress() {
        return connection.remoteAddress();
    }
}
