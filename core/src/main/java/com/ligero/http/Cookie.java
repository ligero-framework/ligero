package com.ligero.http;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable HTTP cookie with the attributes defined by RFC 6265bis.
 * Build instances with {@link #of(String, String)} and the {@code with*}
 * methods.
 */
public final class Cookie {

    private final String name;
    private final String value;
    private final String path;
    private final String domain;
    private final long maxAgeSeconds; // -1 = session cookie
    private final boolean secure;
    private final boolean httpOnly;
    private final String sameSite; // Lax | Strict | None | null

    private Cookie(String name, String value, String path, String domain,
                   long maxAgeSeconds, boolean secure, boolean httpOnly, String sameSite) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cookie name must not be blank");
        }
        this.name = name;
        this.value = value == null ? "" : value;
        this.path = path;
        this.domain = domain;
        this.maxAgeSeconds = maxAgeSeconds;
        this.secure = secure;
        this.httpOnly = httpOnly;
        this.sameSite = sameSite;
    }

    public static Cookie of(String name, String value) {
        return new Cookie(name, value, "/", null, -1, false, true, "Lax");
    }

    public Cookie withPath(String path) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie withDomain(String domain) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie withMaxAge(Duration maxAge) {
        return new Cookie(name, value, path, domain, maxAge.toSeconds(), secure, httpOnly, sameSite);
    }

    public Cookie withSecure(boolean secure) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie withHttpOnly(boolean httpOnly) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    /** @param sameSite one of {@code Lax}, {@code Strict}, {@code None}, or null to omit. */
    public Cookie withSameSite(String sameSite) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    /** Serializes this cookie as a {@code Set-Cookie} header value. */
    public String toSetCookieHeader() {
        StringBuilder sb = new StringBuilder(name).append('=').append(value);
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }
        if (maxAgeSeconds >= 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        }
        if (secure) {
            sb.append("; Secure");
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (sameSite != null) {
            sb.append("; SameSite=").append(sameSite);
        }
        return sb.toString();
    }

    /** Parses a request {@code Cookie} header into a name/value map. */
    public static Map<String, String> parseRequestCookies(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return cookies;
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return cookies;
    }
}
