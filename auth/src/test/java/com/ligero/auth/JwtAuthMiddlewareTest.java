package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.ForbiddenException;
import com.ligero.http.UnauthorizedException;
import com.ligero.json.JacksonBodyMapper;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthMiddlewareTest {

    private final Jwt jwt = Jwt.hs256("0123456789abcdef0123456789abcdef", new JacksonBodyMapper());
    private final JwtAuthMiddleware middleware = JwtAuthMiddleware.of(jwt);

    private static Context context(String authorization) {
        FakeRequest request = FakeRequest.of("GET", "/api/data");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return new Context(request, new FakeResponse(), "/", null, null);
    }

    @Test
    void acceptsValidBearerAndExposesClaims() throws Exception {
        String token = jwt.sign(Map.of("sub", "ada", "roles", List.of("admin")), Duration.ofMinutes(5));
        Context ctx = context("Bearer " + token);
        AtomicBoolean proceeded = new AtomicBoolean();

        middleware.handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isTrue();
        assertThat(ctx.<String>attribute(JwtAuthMiddleware.USER_ATTRIBUTE)).isEqualTo("ada");
        assertThatCode(() -> JwtAuthMiddleware.requireRole(ctx, "admin")).doesNotThrowAnyException();
        assertThatThrownBy(() -> JwtAuthMiddleware.requireRole(ctx, "root"))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsMissingOrNonBearerHeader() {
        assertThatThrownBy(() -> middleware.handle(context(null), () -> { }))
            .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> middleware.handle(context("Basic abc"), () -> { }))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsInvalidToken() {
        assertThatThrownBy(() -> middleware.handle(context("Bearer not.a.token"), () -> { }))
            .isInstanceOf(UnauthorizedException.class);
    }
}
