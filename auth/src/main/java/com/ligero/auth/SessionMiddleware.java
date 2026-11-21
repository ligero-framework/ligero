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

    private static final String SELF_ATTRIBUTE = "ligero.session.middleware";

    private final byte[] secret;
    private final SessionStore store;
    private final boolean secureCookie;

    private SessionMiddleware(byte[] secret, SessionStore store, boolean secureCookie) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("Session secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
        this.store = store;
        this.secureCookie = secureCookie;
    }

    /**
     * Marks the session cookie {@code Secure} so browsers only send it over
     * HTTPS. Turn this on in every deployment served over TLS (directly or
     * behind a proxy):
     *
     * <pre>{@code app.use(SessionMiddleware.of(secret).secureCookie(true));}</pre>
     */
    public SessionMiddleware secureCookie(boolean secure) {
        return new SessionMiddleware(secret, store, secure);
    }

    /**
     * Replaces the current session id with a fresh one, keeping its
     * attributes. Call this right after a successful login (or any privilege
     * change): it defeats session fixation, where an attacker plants a known
     * session id in the victim's browser before they authenticate.
     */
    public static Session regenerate(Context ctx) {
        SessionMiddleware self = require(ctx);
        Session current = ctx.attribute(ATTRIBUTE);
        Session fresh = self.start(ctx);
        if (current != null) {
            current.attributes().forEach(fresh::set);
            self.store.delete(current.id());
        }
        ctx.attribute(ATTRIBUTE, fresh);
        return fresh;
    }

    /** Destroys the current session (logout) and expires its cookie. */
    public static void invalidate(Context ctx) {
        SessionMiddleware self = require(ctx);
        Session current = ctx.attribute(ATTRIBUTE);
        if (current != null) {
            self.store.delete(current.id());
        }
        ctx.attribute(ATTRIBUTE, null);
        ctx.removeCookie(COOKIE);
    }

    private static SessionMiddleware require(Context ctx) {
        SessionMiddleware self = ctx.attribute(SELF_ATTRIBUTE);
        if (self == null) {
            throw new IllegalStateException("No session on this request — is SessionMiddleware installed?");
        }
        return self;
    }

    /** Creates a session and sends its signed cookie. */
    private Session start(Context ctx) {
        String id = B64E.encodeToString(randomBytes(24));
        Session session = store.create(id);
        ctx.setCookie(Cookie.of(COOKIE, id + "." + B64E.encodeToString(hmac(id)))
            .withSameSite("Lax")
            .withSecure(secureCookie));
        return session;
    }

    public static SessionMiddleware of(String secret) {
        return of(secret, new InMemorySessionStore());
    }

    public static SessionMiddleware of(String secret, SessionStore store) {
        return new SessionMiddleware(secret.getBytes(StandardCharsets.UTF_8), store, false);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        Session session = resolve(ctx.cookie(COOKIE));
        if (session == null) {
            session = start(ctx);
        }
        ctx.attribute(ATTRIBUTE, session);
        ctx.attribute(SELF_ATTRIBUTE, this);
        try {
            chain.proceed();
        } finally {
            // flush attribute changes (no-op for the in-memory store); the
            // handler may have regenerated or invalidated the session
            Session current = ctx.attribute(ATTRIBUTE);
            if (current != null) {
                store.save(current);
            }
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
