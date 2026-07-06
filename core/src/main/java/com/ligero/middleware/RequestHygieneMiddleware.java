package com.ligero.middleware;

import com.ligero.http.BadRequestException;
import com.ligero.http.Context;

/**
 * OWASP-aligned input hygiene, part of Ligero's secure-by-default posture
 * (enabled automatically unless {@code secureDefaults(false)}): rejects
 * requests whose path carries null bytes, control characters or encoded
 * traversal sequences before any handler runs.
 */
public final class RequestHygieneMiddleware implements Middleware {

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String rawUri = ctx.req().getUri();
        if (hasControlCharacters(rawUri) || hasEncodedTraversal(rawUri)) {
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

    static boolean hasEncodedTraversal(String uri) {
        String lower = uri.toLowerCase();
        // '..' hidden behind percent-encoding to bypass literal checks
        return lower.contains("%2e%2e") || lower.contains(".%2e") || lower.contains("%2e.");
    }
}
