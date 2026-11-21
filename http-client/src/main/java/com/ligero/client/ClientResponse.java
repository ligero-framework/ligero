package com.ligero.client;

import com.ligero.http.ProblemDetail;
import com.ligero.spi.BodyMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A response received by a {@link RestClient}: status, headers and the whole (size-limited) body. */
public final class ClientResponse {

    private final String request;
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final BodyMapper mapper;

    ClientResponse(String request, int status, Map<String, List<String>> headers, byte[] body, BodyMapper mapper) {
        this.request = request;
        this.status = status;
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> {
            if (name != null) {
                copy.put(name, List.copyOf(values));
            }
        });
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body;
        this.mapper = mapper;
    }

    /** {@code "GET https://api.example.com/products/1"} — for messages and logs. */
    public String request() {
        return request;
    }

    public int status() {
        return status;
    }

    /** True for a {@code 2xx} status. */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** First value of a header (case-insensitive), or {@code null}. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** All headers, case-insensitive. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] bodyAsBytes() {
        return body.clone();
    }

    /** The body as text, decoded as UTF-8. */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The JSON body as {@code type}; {@code null} for an empty body. */
    public <T> T as(Class<T> type) {
        if (type == String.class) {
            return type.cast(bodyAsString());
        }
        if (type == byte[].class) {
            return type.cast(bodyAsBytes());
        }
        if (type == Void.class || body.length == 0) {
            return null;
        }
        return readJson(type);
    }

    /** The JSON array body as a list of {@code elementType}; empty for an empty body. */
    public <T> List<T> asList(Class<T> elementType) {
        if (body.length == 0) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        T[] array = (T[]) readJson(elementType.arrayType());
        return array == null ? List.of() : Collections.unmodifiableList(Arrays.asList(array));
    }

    /** The RFC 9457 problem in the body, when the server sent {@code application/problem+json}. */
    public Optional<ProblemDetail> problem() {
        String contentType = header("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(ProblemDetail.MEDIA_TYPE)
                || mapper == null || body.length == 0 || status < 100 || status > 599) {
            return Optional.empty();
        }
        Map<?, ?> members;
        try {
            members = mapper.readJson(bodyAsString(), Map.class);
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
        if (members == null) {
            return Optional.empty();
        }
        ProblemDetail problem = ProblemDetail.of(status);
        members.forEach((key, value) -> {
            String name = String.valueOf(key);
            switch (name) {
                case "type" -> {
                    if (value instanceof String type) {
                        problem.type(type);
                    }
                }
                case "title" -> problem.title(value == null ? null : value.toString());
                case "detail" -> problem.detail(value == null ? null : value.toString());
                case "instance" -> problem.instance(value == null ? null : value.toString());
                case "status" -> {
                    // the HTTP status is authoritative
                }
                default -> problem.with(name, value);
            }
        });
        return Optional.of(problem);
    }

    private <T> T readJson(Class<T> type) {
        if (mapper == null) {
            throw new IllegalStateException("No BodyMapper to read JSON. Add ligero-json to the classpath "
                + "or pass one with RestClient.builder().bodyMapper(...)");
        }
        try {
            return mapper.readJson(bodyAsString(), type);
        } catch (RuntimeException e) {
            throw new RestClientException("The response of " + request + " is not a valid "
                + type.getSimpleName() + " as JSON", e);
        }
    }

    @Override
    public String toString() {
        return request + " -> " + status;
    }
}
