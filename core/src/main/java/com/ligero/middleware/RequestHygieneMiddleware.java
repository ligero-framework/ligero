package com.ligero.middleware;

import com.ligero.http.BadRequestException;
import com.ligero.http.Context;

/**
 * OWASP-aligned input hygiene, part of Ligero's secure-by-default posture
 * (enabled automatically unless {@code secureDefaults(false)}): rejects
 * requests whose path carries null bytes, control characters, backslashes
 * or dot segments ({@code ..}, plain or percent-encoded) before any handler
 * runs.
 */
public final class RequestHygieneMiddleware implements Middleware {

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (isMalformed(ctx.req().getUri())) {
            throw new BadRequestException("Malformed request path");
        }
        chain.proceed();
    }

    /**
     * Single pass over the raw URI, without allocating: this runs on every
     * request, so it must cost next to nothing for the well-formed ones.
     */
    static boolean isMalformed(String uri) {
        int length = uri.length();
        boolean inPath = true;
        int segmentStart = 0;      // index just after the last '/' seen in the path
        for (int i = 0; i < length; i++) {
            char c = uri.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;                                   // control character
            }
            if (c == '%' && i + 2 < length) {
                char h1 = uri.charAt(i + 1);
                char h2 = uri.charAt(i + 2);
                if (h1 == '0' && h2 == '0') {
                    return true;                               // %00 anywhere
                }
                if (h1 == '2' && (h2 == 'e' || h2 == 'E')) {
                    // an encoded dot next to another dot (plain or encoded) hides ".."
                    if ((i > 0 && uri.charAt(i - 1) == '.') || (i + 3 < length && uri.charAt(i + 3) == '.')
                            || isEncodedDot(uri, i + 3) || (i >= 3 && isEncodedDot(uri, i - 3))) {
                        return true;
                    }
                }
                if (inPath && h1 == '5' && (h2 == 'c' || h2 == 'C')) {
                    return true;                               // encoded backslash in the path
                }
            }
            if (!inPath) {
                continue;
            }
            if (c == '\\') {
                return true;                                   // backslash in the path
            }
            if (c == '/' || c == '?') {
                if (isDotSegment(uri, segmentStart, i)) {
                    return true;
                }
                segmentStart = i + 1;
                inPath = c == '/';
            }
        }
        return inPath && isDotSegment(uri, segmentStart, length);
    }

    private static boolean isEncodedDot(String uri, int at) {
        return at >= 0 && at + 2 < uri.length() && uri.charAt(at) == '%' && uri.charAt(at + 1) == '2'
            && (uri.charAt(at + 2) == 'e' || uri.charAt(at + 2) == 'E');
    }

    /** True when {@code uri[from, to)} is "." or ".." once %2e is read as a dot. */
    private static boolean isDotSegment(String uri, int from, int to) {
        int dots = 0;
        int i = from;
        while (i < to) {
            if (uri.charAt(i) == '.') {
                i++;
            } else if (isEncodedDot(uri, i) && i + 3 <= to) {
                i += 3;
            } else {
                return false;
            }
            if (++dots > 2) {
                return false;
            }
        }
        return dots == 1 || dots == 2;
    }

    static boolean hasControlCharacters(String uri) {
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        // %00 (null byte) in any casing
        String lower = uri.toLowerCase();
        return lower.contains("%00");
    }

    /**
     * Dot segments ({@code /../}, {@code /./}, plain or percent-encoded) and
     * backslashes in the path. Browsers and well-behaved clients resolve these
     * before sending, so on the wire they only show up in traversal attempts.
     */
    static boolean hasTraversalSegment(String uri) {
        int query = uri.indexOf('?');
        String path = (query >= 0 ? uri.substring(0, query) : uri).toLowerCase();
        if (path.indexOf('\\') >= 0 || path.contains("%5c")) {
            return true;
        }
        for (String segment : path.split("/")) {
            String decoded = segment.replace("%2e", ".");
            if (".".equals(decoded) || "..".equals(decoded)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasEncodedTraversal(String uri) {
        String lower = uri.toLowerCase();
        // '..' hidden behind percent-encoding to bypass literal checks
        return lower.contains("%2e%2e") || lower.contains(".%2e") || lower.contains("%2e.");
    }
}
