package com.ligero.versioning;

import com.ligero.http.Context;
import com.ligero.http.Handler;
import com.ligero.http.ProblemDetail;
import com.ligero.http.ProblemException;
import com.ligero.middleware.Middleware;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * API versioning: reads the version a client asks for, refuses versions the
 * application does not serve, and marks deprecated ones in the response.
 *
 * <pre>{@code
 * app.use(ApiVersioning.builder()
 *     .header("API-Version")              // API-Version: 2
 *     .supported("1", "2")
 *     .defaultVersion("2")                // no header -> 2
 *     .deprecate("1", ApiVersioning.Deprecation.since(Instant.parse("2026-06-01T00:00:00Z"))
 *         .sunset(Instant.parse("2027-01-01T00:00:00Z"))
 *         .link("https://example.com/docs/migrate-to-v2"))
 *     .build());
 *
 * // one route, one handler per version
 * app.get("/products", ApiVersioning.handlers()
 *     .version("1", ctx -> ctx.json(catalog.allV1()))
 *     .version("2+", ctx -> ctx.json(catalog.all()))   // 2 and every later version
 *     .build());
 * }</pre>
 *
 * <p>The version can come from a header, a query parameter, a path segment
 * ({@code /v2/products}) or a media type parameter
 * ({@code Accept: application/json;version=2}); with several sources, the
 * first one that carries a version wins. Handlers read it with
 * {@code ctx.apiVersion()}.</p>
 *
 * <p>Responses: an unsupported or malformed version is a {@code 400} problem
 * ({@code application/problem+json}); a deprecated version still works but
 * gets {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) and
 * {@code Link: <...>; rel="deprecation"} headers, so clients and gateways can
 * warn before the version goes away.</p>
 */
public final class ApiVersioning implements Middleware {

    /** Context attribute holding the request's {@link ApiVersion}. */
    public static final String VERSION_ATTRIBUTE = "ligero.apiVersion";

    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final List<Function<Context, String>> sources;
    private final List<String> varyHeaders;
    private final TreeSet<ApiVersion> supported;
    private final ApiVersion defaultVersion;
    private final boolean required;
    private final Map<ApiVersion, Deprecation> deprecations;

    private ApiVersioning(Builder builder) {
        this.sources = List.copyOf(builder.sources);
        this.varyHeaders = List.copyOf(builder.varyHeaders);
        this.supported = new TreeSet<>(builder.supported);
        this.defaultVersion = builder.defaultVersion;
        this.required = builder.required;
        this.deprecations = Map.copyOf(builder.deprecations);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts a route handler that dispatches on the request's version. */
    public static Handlers handlers() {
        return new Handlers();
    }

    /** The version of {@code ctx}, or {@code null} when none was given and there is no default. */
    public static ApiVersion of(Context ctx) {
        Object version = ctx.attribute(VERSION_ATTRIBUTE);
        return version instanceof ApiVersion v ? v : null;
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        varyHeaders.forEach(ctx::vary);
        String requested = null;
        for (Function<Context, String> source : sources) {
            requested = source.apply(ctx);
            if (requested != null && !requested.isBlank()) {
                break;
            }
        }
        ApiVersion version;
        if (requested == null || requested.isBlank()) {
            if (defaultVersion == null && required) {
                throw new ProblemException(ProblemDetail.of(400, "This API requires a version; supported: "
                    + supportedList()).title("API version required"));
            }
            version = defaultVersion;
        } else {
            version = ApiVersion.tryParse(requested);
            if (version == null) {
                throw new ProblemException(ProblemDetail.of(400, "Not a valid API version; supported: "
                    + supportedList()).title("Malformed API version"));
            }
            if (!supported.contains(version)) {
                throw new ProblemException(ProblemDetail.of(400, "API version " + version
                    + " is not supported; supported: " + supportedList()).title("Unsupported API version")
                    .with("supported", supported.stream().map(ApiVersion::toString).toList()));
            }
        }
        if (version != null) {
            ctx.attribute(VERSION_ATTRIBUTE, version);
            Deprecation deprecation = deprecations.get(version);
            if (deprecation != null) {
                deprecation.apply(ctx);
            }
        }
        chain.proceed();
    }

    private String supportedList() {
        return supported.stream().map(ApiVersion::toString).collect(Collectors.joining(", "));
    }

    /** When a deprecated version stops being served, and where to read about it. */
    public static final class Deprecation {
        private final Instant since;
        private Instant sunset;
        private String link;

        private Deprecation(Instant since) {
            this.since = Objects.requireNonNull(since, "since");
        }

        /** Deprecated since {@code since} (may be in the future). */
        public static Deprecation since(Instant since) {
            return new Deprecation(since);
        }

        /** The version stops working at {@code sunset}. */
        public Deprecation sunset(Instant sunset) {
            this.sunset = Objects.requireNonNull(sunset, "sunset");
            return this;
        }

        /** A page explaining the deprecation (migration guide). */
        public Deprecation link(String uri) {
            Objects.requireNonNull(uri, "uri");
            if (!uri.matches("https?://[^\\s<>\"]+")) {
                throw new IllegalArgumentException("Deprecation link must be an absolute http(s) URI: " + uri);
            }
            this.link = uri;
            return this;
        }

        void apply(Context ctx) {
            ctx.header("Deprecation", "@" + since.getEpochSecond());
            if (sunset != null) {
                ctx.header("Sunset", HTTP_DATE.format(sunset));
            }
            if (link != null) {
                ctx.header("Link", "<" + link + ">; rel=\"deprecation\"; type=\"text/html\"");
            }
        }
    }

    /** Builder; at least one source and one supported version are required. */
    public static final class Builder {
        private final List<Function<Context, String>> sources = new ArrayList<>();
        private final List<String> varyHeaders = new ArrayList<>();
        private final List<ApiVersion> supported = new ArrayList<>();
        private final Map<ApiVersion, Deprecation> deprecations = new LinkedHashMap<>();
        private ApiVersion defaultVersion;
        private boolean required;

        /** Reads the version from a request header, e.g. {@code API-Version: 2}. */
        public Builder header(String name) {
            Objects.requireNonNull(name, "name");
            sources.add(ctx -> ctx.header(name));
            varyHeaders.add(name);
            return this;
        }

        /** Reads the version from a query parameter, e.g. {@code ?version=2}. */
        public Builder queryParam(String name) {
            Objects.requireNonNull(name, "name");
            sources.add(ctx -> ctx.queryParam(name));
            return this;
        }

        /**
         * Reads the version from path segment {@code index} (0 = first), e.g.
         * {@code /v2/products} with index 0. A segment that is not a version
         * ({@code /health}) counts as no version. Register the routes with the
         * segment in them ({@code app.group("/v2", ...)} or {@code /{version}/...}).
         */
        public Builder pathSegment(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0");
            }
            sources.add(ctx -> {
                String[] segments = ctx.path().split("/");
                // "/v2/products" -> ["", "v2", "products"]
                int position = index + 1;
                if (position >= segments.length) {
                    return null;
                }
                String segment = segments[position];
                return ApiVersion.tryParse(segment) != null && segment.matches("[vV]?[0-9.]+") ? segment : null;
            });
            return this;
        }

        /**
         * Reads the version from a parameter of the {@code Accept} (or
         * {@code Content-Type}) media type, e.g.
         * {@code Accept: application/vnd.shop+json;version=2}.
         */
        public Builder mediaTypeParameter(String name) {
            Objects.requireNonNull(name, "name");
            sources.add(ctx -> {
                String fromAccept = mediaTypeParameter(ctx.header("Accept"), name);
                return fromAccept != null ? fromAccept : mediaTypeParameter(ctx.header("Content-Type"), name);
            });
            varyHeaders.add("Accept");
            return this;
        }

        /** Versions the application serves. */
        public Builder supported(String... versions) {
            for (String version : versions) {
                supported.add(ApiVersion.parse(version));
            }
            return this;
        }

        /** The version used when a request names none. Must be supported. */
        public Builder defaultVersion(String version) {
            this.defaultVersion = ApiVersion.parse(version);
            return this;
        }

        /** Without a default, refuse requests that name no version ({@code 400}). */
        public Builder required() {
            this.required = true;
            return this;
        }

        /** Marks {@code version} as deprecated. It must be supported. */
        public Builder deprecate(String version, Deprecation deprecation) {
            deprecations.put(ApiVersion.parse(version), Objects.requireNonNull(deprecation, "deprecation"));
            return this;
        }

        public ApiVersioning build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("Say where the version comes from: header(), queryParam(), "
                    + "pathSegment() or mediaTypeParameter()");
            }
            if (supported.isEmpty()) {
                throw new IllegalStateException("List the supported versions with supported(...)");
            }
            if (defaultVersion != null && !supported.contains(defaultVersion)) {
                throw new IllegalStateException("The default version " + defaultVersion + " is not supported");
            }
            for (ApiVersion deprecated : deprecations.keySet()) {
                if (!supported.contains(deprecated)) {
                    throw new IllegalStateException("Deprecated version " + deprecated + " is not supported");
                }
            }
            return new ApiVersioning(this);
        }

        private static String mediaTypeParameter(String header, String name) {
            if (header == null || header.length() > 2048) {
                return null;
            }
            for (String range : header.split(",")) {
                String[] parts = range.split(";");
                for (int i = 1; i < parts.length; i++) {
                    String parameter = parts[i].trim();
                    int eq = parameter.indexOf('=');
                    if (eq > 0 && parameter.substring(0, eq).trim().equalsIgnoreCase(name)) {
                        String value = parameter.substring(eq + 1).trim();
                        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Builds a route handler with one implementation per version.
     * {@code "2"} serves exactly version 2; {@code "2+"} serves 2 and every
     * later version that has no more specific handler. A version with no
     * handler gets a {@code 404} problem ("not available in this version").
     */
    public static final class Handlers {
        private record Entry(ApiVersion version, boolean andLater, Handler handler) {
        }

        private final List<Entry> entries = new ArrayList<>();
        private Handler otherwise;

        private Handlers() {
        }

        /** Handles {@code version} ({@code "2"}) or a baseline and later ({@code "2+"}). */
        public Handlers version(String version, Handler handler) {
            Objects.requireNonNull(handler, "handler");
            String text = Objects.requireNonNull(version, "version").trim();
            boolean andLater = text.endsWith("+");
            ApiVersion parsed = ApiVersion.parse(andLater ? text.substring(0, text.length() - 1) : text);
            for (Entry entry : entries) {
                if (entry.version.equals(parsed) && entry.andLater == andLater) {
                    throw new IllegalStateException("Two handlers for API version " + text);
                }
            }
            entries.add(new Entry(parsed, andLater, handler));
            return this;
        }

        /** Handles requests that name no version, or one no other handler covers. */
        public Handlers otherwise(Handler handler) {
            this.otherwise = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Handler build() {
            if (entries.isEmpty()) {
                throw new IllegalStateException("Add at least one version(...)");
            }
            List<Entry> table = List.copyOf(entries);
            Handler fallback = otherwise;
            return ctx -> {
                ApiVersion requested = ApiVersioning.of(ctx);
                Entry best = null;
                if (requested != null) {
                    for (Entry entry : table) {
                        boolean matches = entry.andLater
                            ? requested.isAtLeast(entry.version)
                            : requested.equals(entry.version);
                        if (matches && (best == null || better(entry, best))) {
                            best = entry;
                        }
                    }
                }
                if (best != null) {
                    best.handler.handle(ctx);
                } else if (fallback != null) {
                    fallback.handle(ctx);
                } else if (requested == null) {
                    throw new ProblemException(ProblemDetail.of(400, "This endpoint needs an API version")
                        .title("API version required"));
                } else {
                    throw new ProblemException(ProblemDetail.of(404, ctx.method() + " " + ctx.path()
                        + " is not available in API version " + requested).title("Not available in this version"));
                }
            };
        }

        /** An exact match beats a baseline; among baselines, the latest one wins. */
        private static boolean better(Entry candidate, Entry current) {
            if (candidate.andLater != current.andLater) {
                return !candidate.andLater;
            }
            return candidate.version.compareTo(current.version) > 0;
        }
    }
}
