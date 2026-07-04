package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BiPredicate;

/**
 * HTTP Basic authentication. On success the username is stored in the
 * {@code user} context attribute; otherwise the request is rejected with
 * {@code 401} and a {@code WWW-Authenticate} challenge.
 *
 * <pre>{@code
 * app.use("/admin", BasicAuthMiddleware.of("Admin area",
 *     (user, password) -> credentialsStore.matches(user, password)));
 * }</pre>
 */
public final class BasicAuthMiddleware implements Middleware {

    public static final String USER_ATTRIBUTE = "user";

    private final String realm;
    private final BiPredicate<String, String> validator;

    private BasicAuthMiddleware(String realm, BiPredicate<String, String> validator) {
        this.realm = realm;
        this.validator = validator;
    }

    public static BasicAuthMiddleware of(String realm, BiPredicate<String, String> validator) {
        return new BasicAuthMiddleware(realm, validator);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                decoded = null;
            }
            int colon = decoded == null ? -1 : decoded.indexOf(':');
            if (colon >= 0) {
                String user = decoded.substring(0, colon);
                String password = decoded.substring(colon + 1);
                if (validator.test(user, password)) {
                    ctx.attribute(USER_ATTRIBUTE, user);
                    chain.proceed();
                    return;
                }
            }
        }
        ctx.header("WWW-Authenticate", "Basic realm=\"" + realm + "\", charset=\"UTF-8\"");
        throw new UnauthorizedException("Authentication required");
    }
}
