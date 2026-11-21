package com.ligero.client;

import com.ligero.spi.BodyMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Calls HTTP APIs from a Ligero application, on the JDK's own
 * {@link java.net.http.HttpClient} — no extra dependency.
 *
 * <pre>{@code
 * RestClient catalog = RestClient.builder()
 *     .baseUrl("https://catalog.internal/api")
 *     .header("Authorization", "Bearer " + token)
 *     .timeout(Duration.ofSeconds(5))
 *     .build();
 *
 * Product product = catalog.get("/products/{id}", 42).retrieve(Product.class);
 * List<Product> cheap = catalog.get("/products").query("maxPrice", 10).retrieveList(Product.class);
 * Optional<Product> maybe = catalog.get("/products/{id}", 7).retrieveOptional(Product.class); // 404 -> empty
 * Product created = catalog.post("/products").body(new NewProduct("Mouse", 25)).retrieve(Product.class);
 * }</pre>
 *
 * <p>Error statuses throw {@link HttpStatusException}, which carries the
 * response and its RFC 9457 problem when there is one; connection failures
 * and timeouts throw {@link RestClientException}. Path variables are
 * percent-encoded as one path segment, so a value can never add segments
 * ({@code "../admin"} becomes {@code ..%2Fadmin}). Responses are read up to
 * {@link Builder#maxResponseBytes(long)}. Redirects are followed by the
 * client itself, a bounded number of times, re-checking the
 * {@link AddressFilter} on every hop and dropping credentials when a
 * redirect leaves the original host.</p>
 *
 * <p>For a typed client generated at compile time, declare an interface
 * with {@code @ClientApi} and let {@code ligero-processor} implement it.</p>
 */
public final class RestClient {

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie", "proxy-authorization");

    private final URI baseUrl;
    private final HttpClient http;
    private final BodyMapper mapper;
    private final Duration timeout;
    private final Map<String, String> defaultHeaders;
    private final AddressFilter addressFilter;
    private final int maxRedirects;
    private final long maxResponseBytes;

    private RestClient(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.mapper = builder.mapper != null ? builder.mapper
            : ServiceLoader.load(BodyMapper.class).findFirst().orElse(null);
        this.timeout = builder.timeout;
        this.defaultHeaders = Map.copyOf(builder.headers);
        this.addressFilter = builder.addressFilter;
        this.maxRedirects = builder.maxRedirects;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.http = builder.http != null ? builder.http : HttpClient.newBuilder()
            .connectTimeout(builder.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER) // followed here, so every hop is checked
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A client with the defaults and no base URL (pass absolute URLs). */
    public static RestClient create() {
        return builder().build();
    }

    public Request get(String path, Object... pathValues) {
        return request("GET", path, pathValues);
    }

    public Request post(String path, Object... pathValues) {
        return request("POST", path, pathValues);
    }

    public Request put(String path, Object... pathValues) {
        return request("PUT", path, pathValues);
    }

    public Request patch(String path, Object... pathValues) {
        return request("PATCH", path, pathValues);
    }

    public Request delete(String path, Object... pathValues) {
        return request("DELETE", path, pathValues);
    }

    /**
     * A request with any method. {@code path} is relative to the base URL (or
     * absolute), and each {@code {name}} placeholder in it is replaced, in
     * order, by the next of {@code pathValues}.
     */
    public Request request(String method, String path, Object... pathValues) {
        return new Request(method.toUpperCase(Locale.ROOT), expand(path, pathValues));
    }

    // ---------------------------------------------------------------- URLs

    private String expand(String template, Object[] values) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int next = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Unclosed '{' in " + template);
                }
                if (values == null || next >= values.length) {
                    throw new IllegalArgumentException("No value for " + template.substring(i, close + 1)
                        + " in " + template);
                }
                Object value = values[next++];
                if (value == null) {
                    throw new IllegalArgumentException("Null value for " + template.substring(i, close + 1));
                }
                out.append(encode(value.toString()));
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        if (values != null && next < values.length) {
            throw new IllegalArgumentException(values.length + " path values for " + next
                + " placeholders in " + template);
        }
        return out.toString();
    }

    /** Percent-encodes everything but the RFC 3986 unreserved characters. */
    static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                   .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private URI resolve(String path, List<String[]> query) {
        String target = path;
        if (!target.matches("(?i)https?://.*")) {
            if (baseUrl == null) {
                throw new IllegalArgumentException("Relative path " + path + " but the client has no baseUrl");
            }
            String base = baseUrl.toString();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            target = base + (target.isEmpty() || target.startsWith("/") || target.startsWith("?") ? "" : "/") + target;
        }
        if (!query.isEmpty()) {
            StringBuilder q = new StringBuilder(target).append(target.contains("?") ? '&' : '?');
            for (int i = 0; i < query.size(); i++) {
                if (i > 0) {
                    q.append('&');
                }
                q.append(encode(query.get(i)[0])).append('=').append(encode(query.get(i)[1]));
            }
            target = q.toString();
        }
        try {
            return URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid URL: " + target, e);
        }
    }

    // ---------------------------------------------------------------- exchange

    private ClientResponse exchange(Request request) {
        URI uri = resolve(request.path, request.query);
        String method = request.method;
        byte[] body = request.body;
        String contentType = request.contentType;
        Map<String, String> headers = new LinkedHashMap<>(defaultHeaders);
        headers.putAll(request.headers);
        String originalAuthority = authority(uri);

        for (int hop = 0; ; hop++) {
            checkTarget(uri);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(request.timeout != null ? request.timeout : timeout)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            if (body != null && contentType != null && !hasHeader(headers, "Content-Type")) {
                builder.header("Content-Type", contentType);
            }
            if (!hasHeader(headers, "Accept")) {
                builder.header("Accept", "application/json, application/problem+json;q=0.9, */*;q=0.5");
            }
            if (!hasHeader(headers, "User-Agent")) {
                builder.header("User-Agent", "ligero-http-client");
            }
            String description = method + " " + uri;
            HttpResponse<InputStream> response = send(builder.build(), description);
            int status = response.statusCode();
            String location = response.headers().firstValue("Location").orElse(null);
            if (REDIRECTS.contains(status) && location != null && hop < maxRedirects) {
                drain(response);
                URI next;
                try {
                    next = uri.resolve(location.trim());
                } catch (IllegalArgumentException e) {
                    throw new RestClientException(description + " redirected to an invalid URL", e);
                }
                if (status == 303 || (status == 301 || status == 302) && "POST".equals(method)) {
                    method = "GET";
                    body = null;
                }
                if (!authority(next).equals(originalAuthority)) {
                    headers.keySet().removeIf(name -> CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
                }
                uri = next;
                continue;
            }
            byte[] content = read(response, description);
            return new ClientResponse(description, status, response.headers().map(), content, mapper);
        }
    }

    private void checkTarget(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RestClientException("Only http and https URLs are allowed: " + uri);
        }
        if (uri.getHost() == null) {
            throw new RestClientException("URL without a host: " + uri);
        }
        if (addressFilter.isActive()) {
            try {
                addressFilter.check(uri.getHost());
            } catch (UnknownHostException e) {
                throw new RestClientException("Unknown host " + uri.getHost(), e);
            }
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request, String description) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new RestClientException(description + " timed out", e);
        } catch (IOException e) {
            throw new RestClientException(description + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException(description + " was interrupted", e);
        }
    }

    private byte[] read(HttpResponse<InputStream> response, String description) {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body()) {
            if (declared > maxResponseBytes) {
                throw new RestClientException(description + " sent " + declared + " bytes, more than the "
                    + maxResponseBytes + " allowed");
            }
            byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxResponseBytes + 1));
            if (content.length > maxResponseBytes) {
                throw new RestClientException(description + " sent more than the " + maxResponseBytes
                    + " bytes allowed");
            }
            return content;
        } catch (IOException e) {
            throw new RestClientException("Reading the response of " + description + " failed", e);
        }
    }

    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.skip(64 * 1024);
        } catch (IOException ignored) {
            // the connection is simply not reused
        }
    }

    private static String authority(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- request

    /** One request being prepared; finish it with one of the {@code retrieve...}, {@code send} or {@code execute} methods. */
    public final class Request {
        private final String method;
        private final String path;
        private final List<String[]> query = new ArrayList<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private String contentType;
        private Duration timeout;

        private Request(String method, String path) {
            this.method = method;
            this.path = path;
        }

        /** Adds a query parameter; {@code null} is skipped and a collection adds one parameter per element. */
        public Request query(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (value instanceof Collection<?> values) {
                values.forEach(element -> query(name, element));
            } else if (value instanceof Optional<?> optional) {
                optional.ifPresent(element -> query(name, element));
            } else if (value != null) {
                query.add(new String[] {name, value.toString()});
            }
            return this;
        }

        /** Sets a header; {@code null} is skipped. */
        public Request header(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (value != null) {
                headers.put(name, value.toString());
            }
            return this;
        }

        /**
         * The request body: an object is sent as JSON, a {@code String} as
         * UTF-8 text, a {@code byte[]} as is.
         */
        public Request body(Object value) {
            if (value == null) {
                body = null;
                contentType = null;
            } else if (value instanceof byte[] bytes) {
                body = bytes.clone();
                contentType = "application/octet-stream";
            } else if (value instanceof String text) {
                body = text.getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain; charset=utf-8";
            } else {
                if (mapper == null) {
                    throw new IllegalStateException("No BodyMapper to write JSON. Add ligero-json to the classpath "
                        + "or pass one with RestClient.builder().bodyMapper(...)");
                }
                body = mapper.writeJson(value).getBytes(StandardCharsets.UTF_8);
                contentType = "application/json";
            }
            return this;
        }

        /** A JSON document you already have as text. */
        public Request json(String json) {
            body = json.getBytes(StandardCharsets.UTF_8);
            contentType = "application/json";
            return this;
        }

        /** An HTML-form body ({@code application/x-www-form-urlencoded}). */
        public Request form(Map<String, ?> fields) {
            StringBuilder encoded = new StringBuilder();
            fields.forEach((name, value) -> {
                if (value != null) {
                    if (!encoded.isEmpty()) {
                        encoded.append('&');
                    }
                    encoded.append(encode(name)).append('=').append(encode(value.toString()));
                }
            });
            body = encoded.toString().getBytes(StandardCharsets.UTF_8);
            contentType = "application/x-www-form-urlencoded";
            return this;
        }

        /** A timeout for this request only. */
        public Request timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sends the request and returns the response, whatever its status. */
        public ClientResponse execute() {
            return exchange(this);
        }

        /** Sends the request; throws {@link HttpStatusException} unless the status is {@code 2xx}. */
        public void send() {
            ok(exchange(this));
        }

        /** Sends the request and reads the body as {@code type} ({@code String}, {@code byte[]} or JSON). */
        public <T> T retrieve(Class<T> type) {
            return ok(exchange(this)).as(type);
        }

        /** Same, but a {@code 404} is an empty result instead of an error. */
        public <T> Optional<T> retrieveOptional(Class<T> type) {
            ClientResponse response = exchange(this);
            if (response.status() == 404) {
                return Optional.empty();
            }
            return Optional.ofNullable(ok(response).as(type));
        }

        /** Sends the request and reads a JSON array of {@code elementType}. */
        public <T> List<T> retrieveList(Class<T> elementType) {
            return ok(exchange(this)).asList(elementType);
        }

        /** The body as text. */
        public String retrieveString() {
            return retrieve(String.class);
        }

        private ClientResponse ok(ClientResponse response) {
            // a redirect that was not followed (too many hops) is not the resource either
            if (response.status() >= 300) {
                throw new HttpStatusException(response);
            }
            return response;
        }
    }

    // ---------------------------------------------------------------- builder

    /** Builder with safe defaults: 5 s to connect, 30 s per request, 5 redirects, 10 MiB responses. */
    public static final class Builder {
        private URI baseUrl;
        private BodyMapper mapper;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration timeout = Duration.ofSeconds(30);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private AddressFilter addressFilter = AddressFilter.allowAll();
        private int maxRedirects = 5;
        private long maxResponseBytes = 10L * 1024 * 1024;
        private HttpClient http;

        /** Base of every relative path, e.g. {@code https://api.example.com/v1}. */
        public Builder baseUrl(String baseUrl) {
            URI uri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL: " + baseUrl);
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("baseUrl must not have a query or fragment: " + baseUrl);
            }
            this.baseUrl = uri;
            return this;
        }

        /** The JSON mapper; by default the one on the classpath ({@code ligero-json}). */
        public Builder bodyMapper(BodyMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /** Time allowed for each request, from sending it to the response headers. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** A header sent with every request, e.g. an API key. */
        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Sends {@code version} in {@code headerName} with every request, for APIs versioned by header. */
        public Builder apiVersion(String headerName, String version) {
            return header(headerName, version);
        }

        /** Which addresses may be contacted; see {@link AddressFilter#publicOnly()}. */
        public Builder addressFilter(AddressFilter addressFilter) {
            this.addressFilter = Objects.requireNonNull(addressFilter, "addressFilter");
            return this;
        }

        /** How many redirects to follow (0 to return redirect responses as they are). */
        public Builder maxRedirects(int maxRedirects) {
            if (maxRedirects < 0) {
                throw new IllegalArgumentException("maxRedirects must be >= 0");
            }
            this.maxRedirects = maxRedirects;
            return this;
        }

        /** Largest response body read; larger ones fail with {@link RestClientException}. */
        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes < 0) {
                throw new IllegalArgumentException("maxResponseBytes must be >= 0");
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /**
         * Uses your own JDK client (proxy, TLS settings, executor). It must
         * not follow redirects itself, or the address checks would be skipped.
         */
        public Builder httpClient(HttpClient http) {
            if (Objects.requireNonNull(http, "http").followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException("The HttpClient must use Redirect.NEVER; "
                    + "RestClient follows redirects itself so each hop can be checked");
            }
            this.http = http;
            return this;
        }

        public RestClient build() {
            return new RestClient(this);
        }
    }
}
