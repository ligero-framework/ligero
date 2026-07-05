package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.ForbiddenException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsrfMiddlewareTest {

    private final CsrfMiddleware csrf = new CsrfMiddleware();

    @Test
    void getIssuesTokenCookie() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/form"), response, "/", null, null);
        AtomicBoolean proceeded = new AtomicBoolean();

        csrf.handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isTrue();
        assertThat(response.headerValue("Set-Cookie")).startsWith("XSRF-TOKEN=");
    }

    @Test
    void postWithMatchingTokenPasses() throws Exception {
        FakeRequest request = FakeRequest.of("POST", "/submit")
            .header("Cookie", "XSRF-TOKEN=tok123")
            .header("X-XSRF-TOKEN", "tok123");
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        AtomicBoolean proceeded = new AtomicBoolean();

        csrf.handle(ctx, () -> proceeded.set(true));
        assertThat(proceeded).isTrue();
    }

    @Test
    void postWithoutOrMismatchedTokenIs403() {
        FakeRequest noHeader = FakeRequest.of("POST", "/submit").header("Cookie", "XSRF-TOKEN=tok123");
        assertThatThrownBy(() -> csrf.handle(
                new Context(noHeader, new FakeResponse(), "/", null, null), () -> { }))
            .isInstanceOf(ForbiddenException.class);

        FakeRequest mismatch = FakeRequest.of("POST", "/submit")
            .header("Cookie", "XSRF-TOKEN=tok123")
            .header("X-XSRF-TOKEN", "other");
        assertThatThrownBy(() -> csrf.handle(
                new Context(mismatch, new FakeResponse(), "/", null, null), () -> { }))
            .isInstanceOf(ForbiddenException.class);
    }
}
