package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.ForbiddenException;
import com.ligero.http.UnauthorizedException;
import com.ligero.middleware.Middleware;

import java.util.Collection;
import java.util.Map;

/**
 * Bearer-token authentication over {@link Jwt}. On success the claims map
 * is stored in the {@code jwt.claims} attribute and the {@code sub} claim
 * in {@code user}; missing or invalid tokens yield {@code 401}.
 *
 * <pre>{@code
 * app.use("/api", JwtAuthMiddleware.of(jwt));
 * app.get("/api/admin", ctx -> {
 *     JwtAuthMiddleware.requireRole(ctx, "admin");
 *     ...
 * });
 * }</pre>
 */
public final class JwtAuthMiddleware implements Middleware {

    public static final String CLAIMS_ATTRIBUTE = "jwt.claims";
    public static final String USER_ATTRIBUTE = "user";

    private final Jwt jwt;

    private JwtAuthMiddleware(Jwt jwt) {
        this.jwt = jwt;
    }

    public static JwtAuthMiddleware of(Jwt jwt) {
        return new JwtAuthMiddleware(jwt);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String authorization = ctx.header("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            ctx.header("WWW-Authenticate", "Bearer");
            throw new UnauthorizedException("Bearer token required");
        }
        Map<String, Object> claims = jwt.verify(authorization.substring(7).trim());
        ctx.attribute(CLAIMS_ATTRIBUTE, claims);
        Object subject = claims.get("sub");
        if (subject != null) {
            ctx.attribute(USER_ATTRIBUTE, String.valueOf(subject));
        }
        chain.proceed();
    }

    /** Asserts the verified token carries the role in its {@code roles} claim (403 otherwise). */
    public static void requireRole(Context ctx, String role) {
        Map<String, Object> claims = ctx.attribute(CLAIMS_ATTRIBUTE);
        if (claims == null || !(claims.get("roles") instanceof Collection<?> roles)
                || !roles.contains(role)) {
            throw new ForbiddenException("Role '" + role + "' required");
        }
    }
}
