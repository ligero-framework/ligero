package com.ligero.http;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Represents an HTTP request. Implementations are provided by a
 * {@link com.ligero.spi.ServerEngine} adapter.
 *
 * <p>Header lookups are case-insensitive as mandated by RFC 9110.</p>
 */
public interface HttpRequest {

    /** HTTP method (GET, POST, ...), always upper-case. */
    String getMethod();

    /** Raw request URI as received (path plus optional query string). */
    String getUri();

    /** Protocol version, e.g. {@code HTTP/1.1}. */
    String getProtocol();

    /**
     * All request headers. Keys are case-insensitive; each header may carry
     * multiple values.
     */
    Map<String, List<String>> getHeaders();

    /** First value of the given header, or {@code null} if absent. */
    default String getHeader(String name) {
        List<String> values = getHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** All values of the given header (empty list if absent). */
    default List<String> getHeaderValues(String name) {
        return getHeaders().getOrDefault(name, List.of());
    }

    /**
     * Decoded query parameters. A parameter present without a value
     * (e.g. {@code ?flag}) maps to an empty string, never {@code null}.
     */
    Map<String, List<String>> getQueryParams();

    /** First value of the given query parameter, or {@code null} if absent. */
    default String getQueryParam(String name) {
        List<String> values = getQueryParams().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** All values of the given query parameter (empty list if absent). */
    default List<String> getQueryParamValues(String name) {
        return getQueryParams().getOrDefault(name, List.of());
    }

    /** Request body as a stream. May enforce a configured size limit. */
    InputStream getBody();

    /** Request body fully read as a UTF-8 string (cached after first read). */
    String getBodyAsString();

    /**
     * Path parameters extracted by the router (e.g. {@code {id}}).
     * Empty unless the request went through the routing pipeline.
     */
    default Map<String, String> getPathParams() {
        return Map.of();
    }

    /** Remote client address (host without port), or {@code null} if unknown. */
    String getRemoteAddress();
}
