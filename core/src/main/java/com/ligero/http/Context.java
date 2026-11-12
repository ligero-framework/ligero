package com.ligero.http;

import com.ligero.router.PathNormalizer;
import com.ligero.spi.BodyMapper;
import com.ligero.spi.TemplateEngine;
import com.ligero.validation.BodyValidator;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-request context handed to {@link Handler handlers} and
 * {@link com.ligero.middleware.Middleware middleware}. Wraps the underlying
 * request/response pair and adds path parameters, per-request attributes,
 * body mapping, cookies and form parsing.
 */
public final class Context {

    /**
     * Attribute under which {@link #json(Object)}, {@link #text(String)} and
     * {@link #html(String)} stash the body they were handed, so development
     * tooling (ligero-devtools) can show the controller's result. It is a plain
     * reference — no serialization happens unless something reads it — so the
     * cost is a single map put.
     */
    public static final String RESPONSE_BODY_ATTRIBUTE = "ligero.responseBody";

    private final HttpRequest request;
    private final HttpResponse response;
    private final String path;
    private final Map<String, String> pathParams = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final BodyMapper bodyMapper;
    private final TemplateEngine templateEngine;
    private final Map<Class<?>, Object> services;
    private Map<String, String> cookies;
    private Map<String, List<String>> formParams;
    private byte[] cachedBodyBytes;
    private Multipart multipart;

    public Context(HttpRequest request, HttpResponse response, String contextPath,
                   BodyMapper bodyMapper, TemplateEngine templateEngine) {
        this(request, response, contextPath, bodyMapper, templateEngine, Map.of());
    }

    public Context(HttpRequest request, HttpResponse response, String contextPath,
                   BodyMapper bodyMapper, TemplateEngine templateEngine,
                   Map<Class<?>, Object> services) {
        this.request = request;
        this.response = response;
        this.services = services;
        String rawPath = request.getUri();
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            rawPath = rawPath.substring(0, q);
        }
        this.path = PathNormalizer.stripContextPath(PathNormalizer.normalize(rawPath), contextPath);
        this.bodyMapper = bodyMapper;
        this.templateEngine = templateEngine;
    }

    /**
     * Returns a service registered with {@code app.register(type, impl)}
     * (lightweight DI: explicit registration, no reflection or scanning).
     */
    public <T> T get(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("No service registered for " + type.getName()
                + ". Register it with app.register(" + type.getSimpleName() + ".class, impl).");
        }
        return type.cast(service);
    }

    // ------------------------------------------------------------------
    // Request side
    // ------------------------------------------------------------------

    /** Underlying request; its {@code getPathParams()} reflects this context. */
    public HttpRequest req() {
        return new RequestView();
    }

    public String method() {
        return request.getMethod();
    }

    /** Normalized request path with the application context path stripped. */
    public String path() {
        return path;
    }

    public String header(String name) {
        return request.getHeader(name);
    }

    public String queryParam(String name) {
        return request.getQueryParam(name);
    }

    public List<String> queryParams(String name) {
        return request.getQueryParamValues(name);
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    /** Path parameter converted to int; responds 400 if absent or not numeric. */
    public int pathParamAsInt(String name) {
        String value = pathParams.get(name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Path parameter '" + name + "' must be an integer");
        }
    }

    /** Path parameter converted to long; responds 400 if absent or not numeric. */
    public long pathParamAsLong(String name) {
        String value = pathParams.get(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Path parameter '" + name + "' must be an integer");
        }
    }

    /** Mutable path-parameter map; populated by the routing pipeline. */
    public Map<String, String> pathParams() {
        return pathParams;
    }

    public String bodyAsString() {
        return request.getBodyAsString();
    }

    public InputStream bodyAsStream() {
        return request.getBody();
    }

    /** Request body fully read as bytes (cached; needed for binary uploads). */
    public byte[] bodyAsBytes() {
        if (cachedBodyBytes == null) {
            try (InputStream in = request.getBody()) {
                cachedBodyBytes = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not read request body", e);
            }
        }
        return cachedBodyBytes;
    }

    /** Parses a {@code multipart/form-data} body (fields + uploaded files). */
    public Multipart multipart() {
        if (multipart == null) {
            String contentType = header("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
                throw new BadRequestException("Request is not multipart/form-data");
            }
            multipart = Multipart.parse(bodyAsBytes(), contentType);
        }
        return multipart;
    }

    /** True when the client's {@code Accept} header admits the MIME type. */
    public boolean accepts(String mimeType) {
        return new Accepts(header("Accept")).accepts(mimeType);
    }

    /**
     * Best MIME type among the offered ones according to the client's
     * {@code Accept} header (q-values and wildcards), or {@code null}.
     */
    public String preferredType(List<String> offered) {
        return new Accepts(header("Accept")).preferred(offered);
    }

    /** Deserializes the JSON request body. Requires a {@link BodyMapper} on the classpath. */
    public <T> T body(Class<T> type) {
        String raw = request.getBodyAsString();
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Request body is required");
        }
        T value = requireBodyMapper().readJson(raw, type);
        if (value == null) {
            // a literal JSON null would otherwise surface as a NullPointerException (500)
            throw new BadRequestException("Request body is required");
        }
        return value;
    }

    /** Deserializes the body and returns a validator to declare constraints on it. */
    public <T> BodyValidator<T> bodyValidator(Class<T> type) {
        return new BodyValidator<>(body(type));
    }

    /** Form parameter from an {@code application/x-www-form-urlencoded} body. */
    public String formParam(String name) {
        List<String> values = formParams().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public Map<String, List<String>> formParams() {
        if (formParams == null) {
            String contentType = header("Content-Type");
            if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
                formParams = multipart().fields();
            } else {
                formParams = parseUrlEncoded(request.getBodyAsString());
            }
        }
        return formParams;
    }

    public String cookie(String name) {
        return cookieMap().get(name);
    }

    public Map<String, String> cookieMap() {
        if (cookies == null) {
            cookies = Cookie.parseRequestCookies(request.getHeader("Cookie"));
        }
        return cookies;
    }

    public String remoteAddress() {
        return request.getRemoteAddress();
    }

    // ------------------------------------------------------------------
    // Response side
    // ------------------------------------------------------------------

    public HttpResponse res() {
        return response;
    }

    public Context status(int statusCode) {
        response.status(statusCode);
        return this;
    }

    public Context header(String name, String value) {
        response.header(name, value);
        return this;
    }

    public Context json(Object body) {
        attributes.put(RESPONSE_BODY_ATTRIBUTE, body);
        response.json(body);
        return this;
    }

    public Context text(String body) {
        attributes.put(RESPONSE_BODY_ATTRIBUTE, body);
        response.contentType("text/plain; charset=utf-8").send(body);
        return this;
    }

    public Context html(String body) {
        attributes.put(RESPONSE_BODY_ATTRIBUTE, body);
        response.contentType("text/html; charset=utf-8").send(body);
        return this;
    }

    /**
     * Writes whatever a typed controller method returned — the single place
     * that turns a Java value into an HTTP response:
     *
     * <ul>
     *   <li>{@link Response} — its status and headers are applied, then its body follows these rules;</li>
     *   <li>{@link java.util.Optional} — empty answers {@code 404}, otherwise its value is written;</li>
     *   <li>{@code null} (and {@code void} methods) — no body; {@code 204} unless a status was already set;</li>
     *   <li>{@code String} / {@code CharSequence} — {@code text/plain};</li>
     *   <li>{@code byte[]} — {@code application/octet-stream};</li>
     *   <li>anything else — JSON through the {@link BodyMapper}.</li>
     * </ul>
     *
     * <p>Does nothing when the response is already committed, so a method
     * that wrote through {@code ctx} itself is left alone.</p>
     */
    public Context respond(Object result) {
        if (response.isCommitted()) {
            return this;
        }
        Object body = result;
        if (body instanceof Response<?> typed) {
            response.status(typed.status());
            typed.headers().forEach(response::header);
            body = typed.body();
        }
        if (body instanceof java.util.Optional<?> optional) {
            if (optional.isEmpty()) {
                throw new NotFoundException("Not found");
            }
            body = optional.get();
        }
        if (body == null) {
            if (response.getStatus() == 200) {
                response.status(204);
            }
            response.end();
            return this;
        }
        if (body instanceof CharSequence text) {
            return text(text.toString());
        }
        if (body instanceof byte[] bytes) {
            attributes.put(RESPONSE_BODY_ATTRIBUTE, bytes);
            response.contentType("application/octet-stream");
            try (java.io.OutputStream out = response.getOutputStream()) {
                out.write(bytes);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Could not send response", e);
            }
            return this;
        }
        return json(body);
    }

    public Context redirect(String url) {
        response.redirect(url);
        return this;
    }

    public Context redirect(String url, int statusCode) {
        response.redirect(url, statusCode);
        return this;
    }

    public Context setCookie(Cookie cookie) {
        response.header("Set-Cookie", cookie.toSetCookieHeader());
        return this;
    }

    public Context removeCookie(String name) {
        response.header("Set-Cookie", Cookie.of(name, "").withMaxAge(java.time.Duration.ZERO)
            .withHttpOnly(false).withSameSite(null).toSetCookieHeader());
        return this;
    }

    /**
     * Renders a template through the {@link TemplateEngine} SPI and sends it
     * as HTML.
     */
    public Context render(String templateName, Map<String, Object> model) {
        if (templateEngine == null) {
            throw new IllegalStateException(
                "No TemplateEngine found. Add a ligero-template-* module to the classpath.");
        }
        return html(templateEngine.render(templateName, model));
    }

    /** Starts a Server-Sent Events stream; the response is committed. */
    public SseEmitter sse() {
        return new SseEmitter(response);
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    public Context attribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private BodyMapper requireBodyMapper() {
        if (bodyMapper == null) {
            throw new IllegalStateException(
                "No BodyMapper found. Add ligero-json (or another BodyMapper implementation) to the classpath.");
        }
        return bodyMapper;
    }

    private static Map<String, List<String>> parseUrlEncoded(String body) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.computeIfAbsent(decode(key), k -> new ArrayList<>()).add(decode(value));
        }
        return params;
    }

    private static String decode(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // broken percent-encoding is the client's mistake, not a server error
            throw new BadRequestException("Malformed percent-encoding in form body");
        }
    }

    /** Request view exposing this context's path parameters (legacy-style handlers). */
    private final class RequestView implements HttpRequest {

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        @Override
        public String getUri() {
            return request.getUri();
        }

        @Override
        public String getProtocol() {
            return request.getProtocol();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return request.getHeaders();
        }

        @Override
        public Map<String, List<String>> getQueryParams() {
            return request.getQueryParams();
        }

        @Override
        public InputStream getBody() {
            return request.getBody();
        }

        @Override
        public String getBodyAsString() {
            return request.getBodyAsString();
        }

        @Override
        public Map<String, String> getPathParams() {
            return Map.copyOf(pathParams);
        }

        @Override
        public String getRemoteAddress() {
            return request.getRemoteAddress();
        }
    }
}
