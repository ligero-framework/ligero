package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.Cookie;
import com.ligero.http.ForbiddenException;
import com.ligero.middleware.Middleware;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * CSRF protection using the stateless double-submit-cookie pattern: safe
 * methods receive an {@code XSRF-TOKEN} cookie; unsafe methods must echo
 * its value in the {@code X-XSRF-TOKEN} header (which cross-site forms
 * cannot do), or they are rejected with {@code 403}.
 */
public final class CsrfMiddleware implements Middleware {

    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-XSRF-TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String token = ctx.cookie(COOKIE);
        if (SAFE_METHODS.contains(ctx.method())) {
            if (token == null) {
                // HttpOnly=false on purpose: the front-end must read it to echo it back
                ctx.setCookie(Cookie.of(COOKIE, newToken()).withHttpOnly(false).withSameSite("Lax"));
            }
            chain.proceed();
            return;
        }
        String header = ctx.header(HEADER);
        if (token == null || header == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("CSRF token missing or invalid");
        }
        chain.proceed();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
