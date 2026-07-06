package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CorsMiddlewareTest {

    @Test
    void requestsWithoutOriginPassThroughUntouched() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/data"), response, "/", null, null);
        AtomicBoolean proceeded = new AtomicBoolean();

        CorsMiddleware.permissive().handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isTrue();
        assertThat(response.headerValue("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void decoratesSimpleRequestsWithCorsHeaders() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("GET", "/data").header("Origin", "https://a.example");
        Context ctx = new Context(request, response, "/", null, null);
        AtomicBoolean proceeded = new AtomicBoolean();

        CorsMiddleware.permissive().handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isTrue();
        assertThat(response.headerValue("Access-Control-Allow-Origin")).isEqualTo("*");
    }

    @Test
    void handlesPreflightWithoutInvokingChain() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("OPTIONS", "/data")
            .header("Origin", "https://a.example")
            .header("Access-Control-Request-Method", "POST");
        Context ctx = new Context(request, response, "/", null, null);
        AtomicBoolean proceeded = new AtomicBoolean();

        CorsMiddleware.builder()
            .allowOrigins("https://a.example")
            .allowMethods("GET", "POST")
            .build()
            .handle(ctx, () -> proceeded.set(true));

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.headerValue("Access-Control-Allow-Origin")).isEqualTo("https://a.example");
        assertThat(response.headerValue("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.isCommitted()).isTrue();
    }

    @Test
    void rejectsPreflightFromDisallowedOrigin() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("OPTIONS", "/data")
            .header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "POST");
        Context ctx = new Context(request, response, "/", null, null);

        CorsMiddleware.builder().allowOrigins("https://a.example").build()
            .handle(ctx, () -> { throw new AssertionError("chain must not run"); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.headerValue("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void echoesOriginWhenCredentialsAreAllowed() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("GET", "/data").header("Origin", "https://a.example");
        Context ctx = new Context(request, response, "/", null, null);

        CorsMiddleware.builder().allowCredentials(true).build().handle(ctx, () -> { });

        assertThat(response.headerValue("Access-Control-Allow-Origin")).isEqualTo("https://a.example");
        assertThat(response.headerValue("Access-Control-Allow-Credentials")).isEqualTo("true");
    }
}
