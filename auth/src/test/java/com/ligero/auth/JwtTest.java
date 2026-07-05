package com.ligero.auth;

import com.ligero.http.UnauthorizedException;
import com.ligero.json.JacksonBodyMapper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Jwt jwt = Jwt.hs256(SECRET, new JacksonBodyMapper());

    @Test
    void signAndVerifyRoundTrip() {
        String token = jwt.sign(Map.of("sub", "ada", "roles", List.of("admin")), Duration.ofMinutes(5));
        Map<String, Object> claims = jwt.verify(token);
        assertThat(claims.get("sub")).isEqualTo("ada");
        assertThat((Iterable<?>) claims.get("roles")).isEqualTo(List.of("admin"));
        assertThat(claims).containsKeys("iat", "exp");
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwt.sign(Map.of("sub", "ada"), Duration.ofSeconds(-10));
        assertThatThrownBy(() -> jwt.verify(token))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void rejectsTamperedPayload() {
        String token = jwt.sign(Map.of("sub", "ada"), Duration.ofMinutes(5));
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"eve\"}".getBytes()) + "." + parts[2];
        assertThatThrownBy(() -> jwt.verify(forged)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsWrongSecret() {
        String token = Jwt.hs256("another-secret-of-32-bytes-long!", new JacksonBodyMapper())
            .sign(Map.of("sub", "ada"), Duration.ofMinutes(5));
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsMalformedAndNoneAlgTokens() {
        assertThatThrownBy(() -> jwt.verify("abc")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> jwt.verify(null)).isInstanceOf(UnauthorizedException.class);
        // header with alg=none
        String noneHeader = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        assertThatThrownBy(() -> jwt.verify(noneHeader + ".e30.sig"))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> Jwt.hs256("short", new JacksonBodyMapper()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
