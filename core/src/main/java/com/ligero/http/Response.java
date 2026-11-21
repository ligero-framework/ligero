package com.ligero.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed HTTP response for controller methods that decide the status or
 * headers at runtime. Immutable; build it with the factories and
 * {@link #header(String, String)}.
 *
 * <pre>{@code
 * @Post
 * Response<Product> create(@Body NewProduct body) {
 *     Product created = products.create(body);
 *     return Response.created("/api/products/" + created.id(), created);
 * }
 * }</pre>
 *
 * <p>Written by {@link Context#respond(Object)}; the body follows the same
 * rules as a plain return value (JSON for objects, text for strings, no body
 * for {@code null}).</p>
 *
 * @param <T> type of the body, which keeps the endpoint's contract visible
 *            in the method signature
 */
public final class Response<T> {

    private final int status;
    private final Map<String, String> headers;
    private final T body;

    private Response(int status, Map<String, String> headers, T body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Not an HTTP status code: " + status);
        }
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    /** {@code 200 OK} with a body. */
    public static <T> Response<T> ok(T body) {
        return new Response<>(200, Map.of(), body);
    }

    /** {@code 201 Created} with a body. */
    public static <T> Response<T> created(T body) {
        return new Response<>(201, Map.of(), body);
    }

    /** {@code 201 Created} with a {@code Location} header and a body. */
    public static <T> Response<T> created(String location, T body) {
        return new Response<T>(201, Map.of(), body).header("Location", location);
    }

    /** {@code 202 Accepted} with a body. */
    public static <T> Response<T> accepted(T body) {
        return new Response<>(202, Map.of(), body);
    }

    /** {@code 204 No Content}. */
    public static <T> Response<T> noContent() {
        return new Response<>(204, Map.of(), null);
    }

    /** Any status, without a body. */
    public static <T> Response<T> status(int status) {
        return new Response<>(status, Map.of(), null);
    }

    /** Any status, with a body. */
    public static <T> Response<T> of(int status, T body) {
        return new Response<>(status, Map.of(), body);
    }

    /** A copy of this response with the header added (or replaced). */
    public Response<T> header(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Response<>(status, Collections.unmodifiableMap(copy), body);
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public T body() {
        return body;
    }
}
