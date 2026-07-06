package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersMiddlewareTest {

    @Test
    void addsDefaultHeaders() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/"), response, "/", null, null);

        SecurityHeadersMiddleware.defaults().handle(ctx, () -> { });

        assertThat(response.headerValue("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.headerValue("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.headerValue("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.headerValue("Strict-Transport-Security")).isNull();
    }

    @Test
    void addsHstsAndCspWhenConfigured() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/"), response, "/", null, null);

        SecurityHeadersMiddleware.builder()
            .hsts(Duration.ofDays(365))
            .contentSecurityPolicy("default-src 'self'")
            .build()
            .handle(ctx, () -> { });

        assertThat(response.headerValue("Strict-Transport-Security")).contains("max-age=31536000");
        assertThat(response.headerValue("Content-Security-Policy")).isEqualTo("default-src 'self'");
    }
}
