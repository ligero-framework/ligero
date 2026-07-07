package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.Cookie;
import com.ligero.middleware.Middleware;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Optional cookie-based sessions. The session id travels in an
 * HMAC-signed cookie ({@code id.signature}) so it cannot be forged;
 * state lives behind the {@link SessionStore} SPI (in-memory by default).
 *
 * <pre>{@code
 * app.use(SessionMiddleware.of(secret));
 * app.get("/me", ctx -> {
 *     Session session = ctx.attribute(SessionMiddleware.ATTRIBUTE);
 *     ...
 * });
 * }</pre>
 */
public final class SessionMiddleware implements Middleware {

    public static final String COOKIE = "LIGERO_SESSION";
    public static final String ATTRIBUTE = "session";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final SessionStore store;

    private SessionMiddleware(byte[] secret, SessionStore store) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("Session secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
        this.store = store;
    }

    public static SessionMiddleware of(String secret) {
        return of(secret, new InMemorySessionStore());
    }

    public static SessionMiddleware of(String secret, SessionStore store) {
        return new SessionMiddleware(secret.getBytes(StandardCharsets.UTF_8), store);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        Session session = resolve(ctx.cookie(COOKIE));
        if (session == null) {
            String id = B64E.encodeToString(randomBytes(24));
            session = store.create(id);
            ctx.setCookie(Cookie.of(COOKIE, id + "." + B64E.encodeToString(hmac(id)))
                .withSameSite("Lax"));
        }
        ctx.attribute(ATTRIBUTE, session);
        try {
            chain.proceed();
        } finally {
            // flush attribute changes (no-op for the in-memory store)
            store.save(session);
        }
    }

    /** Returns the session for a valid signed cookie, or null (tampered/unknown). */
    private Session resolve(String cookieValue) {
        if (cookieValue == null) {
            return null;
        }
        int dot = cookieValue.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String id = cookieValue.substring(0, dot);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(id), signature)) {
            return null;
        }
        return store.find(id);
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
