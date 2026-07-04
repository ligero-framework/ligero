package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.UnauthorizedException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BasicAuthMiddlewareTest {

    private final BasicAuthMiddleware auth = BasicAuthMiddleware.of("test",
        (user, password) -> "admin".equals(user) && "secret".equals(password));

    private static Context context(String authorizationHeader) {
        FakeRequest request = FakeRequest.of("GET", "/admin");
        if (authorizationHeader != null) {
            request.header("Authorization", authorizationHeader);
        }
        return new Context(request, new FakeResponse(), "/", null, null);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsValidCredentialsAndExposesUser() throws Exception {
        Context ctx = context(basic("admin", "secret"));
        AtomicBoolean proceeded = new AtomicBoolean();

        auth.handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isTrue();
        assertThat(ctx.<String>attribute(BasicAuthMiddleware.USER_ATTRIBUTE)).isEqualTo("admin");
    }

    @Test
    void rejectsMissingHeader() {
        Context ctx = context(null);
        assertThatThrownBy(() -> auth.handle(ctx, () -> { }))
            .isInstanceOf(UnauthorizedException.class);
        assertThat(ctx.res().isCommitted()).isFalse();
    }

    @Test
    void rejectsWrongPassword() {
        assertThatThrownBy(() -> auth.handle(context(basic("admin", "nope")), () -> { }))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsMalformedBase64() {
        assertThatThrownBy(() -> auth.handle(context("Basic !!!"), () -> { }))
            .isInstanceOf(UnauthorizedException.class);
    }
}
