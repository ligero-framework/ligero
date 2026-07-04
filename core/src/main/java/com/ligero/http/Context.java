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

    private final HttpRequest request;
    private final HttpResponse response;
    private final String path;
    private final Map<String, String> pathParams = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final BodyMapper bodyMapper;
    private final TemplateEngine templateEngine;
    private Map<String, String> cookies;
    private Map<String, List<String>> formParams;

    public Context(HttpRequest request, HttpResponse response, String contextPath,
                   BodyMapper bodyMapper, TemplateEngine templateEngine) {
        this.request = request;
        this.response = response;
        String rawPath = request.getUri();
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            rawPath = rawPath.substring(0, q);
        }
        this.path = PathNormalizer.stripContextPath(PathNormalizer.normalize(rawPath), contextPath);
        this.bodyMapper = bodyMapper;
        this.templateEngine = templateEngine;
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

    /** Deserializes the JSON request body. Requires a {@link BodyMapper} on the classpath. */
    public <T> T body(Class<T> type) {
        return requireBodyMapper().readJson(request.getBodyAsString(), type);
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
            formParams = parseUrlEncoded(request.getBodyAsString());
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
        response.json(body);
        return this;
    }

    public Context text(String body) {
        response.contentType("text/plain; charset=utf-8").send(body);
        return this;
    }

    public Context html(String body) {
        response.contentType("text/html; charset=utf-8").send(body);
        return this;
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
        response.header("Set-Cookie", name + "=; Path=/; Max-Age=0");
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
            params.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), k -> new ArrayList<>())
                  .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
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
