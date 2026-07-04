package com.ligero.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CookieTest {

    @Test
    void serializesAllAttributes() {
        String header = Cookie.of("session", "abc")
            .withDomain("example.com")
            .withPath("/app")
            .withMaxAge(Duration.ofHours(1))
            .withSecure(true)
            .withSameSite("Strict")
            .toSetCookieHeader();

        assertThat(header)
            .startsWith("session=abc")
            .contains("Path=/app")
            .contains("Domain=example.com")
            .contains("Max-Age=3600")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Strict");
    }

    @Test
    void defaultsAreHttpOnlySessionCookie() {
        String header = Cookie.of("a", "b").toSetCookieHeader();
        assertThat(header).contains("HttpOnly").doesNotContain("Max-Age").doesNotContain("Secure");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> Cookie.of(" ", "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesRequestCookieHeader() {
        Map<String, String> cookies = Cookie.parseRequestCookies("a=1; b=2 ; c=x=y");
        assertThat(cookies).containsEntry("a", "1").containsEntry("b", "2").containsEntry("c", "x=y");
        assertThat(Cookie.parseRequestCookies(null)).isEmpty();
    }
}
