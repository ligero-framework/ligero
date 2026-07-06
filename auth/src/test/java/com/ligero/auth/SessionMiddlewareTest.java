package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMiddlewareTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final InMemorySessionStore store = new InMemorySessionStore();
    private final SessionMiddleware middleware = SessionMiddleware.of(SECRET, store);

    @Test
    void createsSessionAndSignedCookieOnFirstRequest() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/"), response, "/", null, null);

        middleware.handle(ctx, () -> { });

        Session session = ctx.attribute(SessionMiddleware.ATTRIBUTE);
        assertThat(session).isNotNull();
        String cookie = response.headerValue("Set-Cookie");
        assertThat(cookie).startsWith("LIGERO_SESSION=").contains(".");
    }

    @Test
    void reusesSessionForValidCookieAndKeepsState() throws Exception {
        FakeResponse first = new FakeResponse();
        Context firstCtx = new Context(FakeRequest.of("GET", "/"), first, "/", null, null);
        middleware.handle(firstCtx, () -> { });
        Session created = firstCtx.attribute(SessionMiddleware.ATTRIBUTE);
        created.set("cart", 3);

        String cookieValue = first.headerValue("Set-Cookie").split(";")[0].split("=", 2)[1];
        FakeRequest request = FakeRequest.of("GET", "/").header("Cookie", "LIGERO_SESSION=" + cookieValue);
        Context secondCtx = new Context(request, new FakeResponse(), "/", null, null);
        middleware.handle(secondCtx, () -> { });

        Session resumed = secondCtx.attribute(SessionMiddleware.ATTRIBUTE);
        assertThat(resumed.id()).isEqualTo(created.id());
        assertThat((Integer) resumed.get("cart")).isEqualTo(3);
    }

    @Test
    void tamperedCookieGetsFreshSession() throws Exception {
        FakeRequest request = FakeRequest.of("GET", "/")
            .header("Cookie", "LIGERO_SESSION=forged-id.invalidsig");
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(request, response, "/", null, null);

        middleware.handle(ctx, () -> { });

        Session session = ctx.attribute(SessionMiddleware.ATTRIBUTE);
        assertThat(session.id()).isNotEqualTo("forged-id");
        assertThat(response.headerValue("Set-Cookie")).isNotNull();
    }
}
