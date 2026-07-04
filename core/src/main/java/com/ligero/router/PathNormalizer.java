package com.ligero.router;

/**
 * Single source of truth for path normalization (architecture rule #3).
 * All framework components must use this class instead of ad-hoc string
 * handling.
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    /**
     * Normalizes a request or route path: ensures a leading slash, collapses
     * duplicate slashes and removes the trailing slash (except for the root).
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder(path.length() + 1);
        if (path.charAt(0) != '/') {
            sb.append('/');
        }
        char prev = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && prev == '/') {
                continue;
            }
            sb.append(c);
            prev = c;
        }
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * Normalizes a context path: blank or {@code "/"} becomes {@code "/"};
     * otherwise a leading slash is ensured and the trailing one removed.
     */
    public static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank()) {
            return "/";
        }
        return normalize(contextPath.trim());
    }

    /**
     * Removes the application context path from an already-normalized request
     * path. Returns the path unchanged when it is outside the context.
     */
    public static String stripContextPath(String path, String contextPath) {
        if (contextPath == null || "/".equals(contextPath) || path == null) {
            return path == null ? "/" : path;
        }
        if (path.equals(contextPath)) {
            return "/";
        }
        if (path.startsWith(contextPath + "/")) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    /** Splits a normalized path into its segments; the root has none. */
    public static String[] segments(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty() || "/".equals(normalizedPath)) {
            return new String[0];
        }
        // skip the leading slash before splitting
        return normalizedPath.substring(1).split("/");
    }
}
