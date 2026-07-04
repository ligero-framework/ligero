package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.router.PathNormalizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves static files from an external directory or the classpath.
 * Requests that do not resolve to a file fall through to the next element
 * of the pipeline, so routes and fallbacks keep working.
 *
 * <p>Path traversal is blocked by normalizing the request path and, for
 * external directories, verifying the resolved file stays under the root.</p>
 *
 * <pre>{@code
 * app.use(StaticFilesMiddleware.external("/static", Path.of("public")));
 * app.use(StaticFilesMiddleware.classpath("/assets", "web"));
 * }</pre>
 */
public final class StaticFilesMiddleware implements Middleware {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"),
        Map.entry("htm", "text/html; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("js", "application/javascript; charset=utf-8"),
        Map.entry("mjs", "application/javascript; charset=utf-8"),
        Map.entry("json", "application/json"),
        Map.entry("txt", "text/plain; charset=utf-8"),
        Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("woff", "font/woff"),
        Map.entry("woff2", "font/woff2"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("wasm", "application/wasm"));

    private final String urlPrefix;
    private final Path externalRoot;      // one of externalRoot / classpathRoot is set
    private final String classpathRoot;
    private final String cacheControl;
    private final String indexFile;

    private StaticFilesMiddleware(String urlPrefix, Path externalRoot, String classpathRoot,
                                  String cacheControl, String indexFile) {
        this.urlPrefix = PathNormalizer.normalize(urlPrefix);
        this.externalRoot = externalRoot;
        this.classpathRoot = classpathRoot;
        this.cacheControl = cacheControl;
        this.indexFile = indexFile;
    }

    /** Serves files from a directory on disk. */
    public static StaticFilesMiddleware external(String urlPrefix, Path directory) {
        return new StaticFilesMiddleware(urlPrefix, directory.toAbsolutePath().normalize(),
            null, "public, max-age=3600", "index.html");
    }

    /** Serves resources bundled on the classpath under {@code resourceRoot}. */
    public static StaticFilesMiddleware classpath(String urlPrefix, String resourceRoot) {
        String root = resourceRoot.endsWith("/")
            ? resourceRoot.substring(0, resourceRoot.length() - 1) : resourceRoot;
        return new StaticFilesMiddleware(urlPrefix, null, root, "public, max-age=3600", "index.html");
    }

    public StaticFilesMiddleware cacheControl(String cacheControl) {
        return new StaticFilesMiddleware(urlPrefix, externalRoot, classpathRoot, cacheControl, indexFile);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (!"GET".equals(ctx.method()) && !"HEAD".equals(ctx.method())) {
            chain.proceed();
            return;
        }
        String path = ctx.path();
        String relative;
        if (path.equals(urlPrefix)) {
            relative = indexFile;
        } else if (path.startsWith(urlPrefix + "/") || "/".equals(urlPrefix)) {
            relative = "/".equals(urlPrefix) ? path.substring(1) : path.substring(urlPrefix.length() + 1);
        } else {
            chain.proceed();
            return;
        }

        if (!isSafe(relative)) {
            chain.proceed();
            return;
        }

        boolean served = externalRoot != null
            ? serveExternal(ctx, relative)
            : serveClasspath(ctx, relative);
        if (!served) {
            chain.proceed();
        }
    }

    /** Rejects traversal sequences and other suspicious segments outright. */
    private static boolean isSafe(String relative) {
        if (relative.isEmpty() || relative.contains("\0") || relative.contains("\\")) {
            return false;
        }
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean serveExternal(Context ctx, String relative) throws IOException {
        Path file = externalRoot.resolve(relative).normalize();
        // defense in depth: even after isSafe(), never escape the root
        if (!file.startsWith(externalRoot) || !Files.isRegularFile(file)) {
            return false;
        }
        String etag = '"' + Long.toHexString(Files.getLastModifiedTime(file).toMillis())
            + '-' + Long.toHexString(Files.size(file)) + '"';
        if (etag.equals(ctx.header("If-None-Match"))) {
            ctx.header("ETag", etag).status(304).res().end();
            return true;
        }
        prepareHeaders(ctx, relative);
        ctx.header("ETag", etag);
        if ("HEAD".equals(ctx.method())) {
            ctx.res().end();
            return true;
        }
        try (OutputStream out = ctx.res().getOutputStream()) {
            Files.copy(file, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    private boolean serveClasspath(Context ctx, String relative) throws IOException {
        String resource = classpathRoot + "/" + relative;
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return false;
        }
        try (in) {
            prepareHeaders(ctx, relative);
            if ("HEAD".equals(ctx.method())) {
                ctx.res().end();
                return true;
            }
            try (OutputStream out = ctx.res().getOutputStream()) {
                in.transferTo(out);
            }
        }
        return true;
    }

    private void prepareHeaders(Context ctx, String relative) {
        int dot = relative.lastIndexOf('.');
        String extension = dot >= 0 ? relative.substring(dot + 1).toLowerCase() : "";
        ctx.res().contentType(CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        if (cacheControl != null) {
            ctx.header("Cache-Control", cacheControl);
        }
    }
}
