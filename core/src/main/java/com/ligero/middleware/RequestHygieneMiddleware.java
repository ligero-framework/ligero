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
        String rawUri = ctx.req().getUri();
        if (hasControlCharacters(rawUri) || hasEncodedTraversal(rawUri) || hasTraversalSegment(rawUri)) {
            throw new BadRequestException("Malformed request path");
        }
        chain.proceed();
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
