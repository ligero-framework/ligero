package com.ligero.auth;

import com.ligero.http.Context;
import com.ligero.http.UnauthorizedException;
import com.ligero.json.JacksonBodyMapper;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regression tests for the JWT and session hardening. */
class AuthHardeningTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Jwt jwt = Jwt.hs256(SECRET, new JacksonBodyMapper());

    /** A token correctly signed with the shared secret but with an arbitrary header/payload. */
    private static String signed(String headerJson, String payloadJson) throws Exception {
        String input = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
            + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void algorithmIsMatchedExactlyNotBySubstring() throws Exception {
        // "HS256" appears in the header, but the declared alg is something else
        String token = signed("{\"alg\":\"none\",\"kid\":\"HS256\"}", "{\"sub\":\"eve\"}");
        assertThatThrownBy(() -> jwt.verify(token))
            .isInstanceOf(UnauthorizedException.class).hasMessageContaining("algorithm");
    }

    @Test
    void headerMustBeJson() throws Exception {
        String token = signed("\"HS256\"", "{\"sub\":\"eve\"}");
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void nonNumericExpiryIsRejectedInsteadOfIgnored() throws Exception {
        String token = signed("{\"alg\":\"HS256\"}", "{\"sub\":\"eve\",\"exp\":\"never\"}");
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void validTokenStillVerifies() throws Exception {
        long future = System.currentTimeMillis() / 1000 + 600;
        String token = signed("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", "{\"sub\":\"ada\",\"exp\":" + future + "}");
        assertThat(jwt.verify(token)).containsEntry("sub", "ada");
    }

    @Test
    void malformedEs256SignatureIs401NotACrash() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();
        Jwt es = Jwt.es256(keys.getPrivate(), keys.getPublic(), new JacksonBodyMapper());
        String[] parts = es.sign(Map.of("sub", "ada"), Duration.ofMinutes(5)).split("\\.");

        for (String badSignature : new String[] {"", "AA", B64.encodeToString(new byte[63]), B64.encodeToString(new byte[200])}) {
            String token = parts[0] + "." + parts[1] + "." + badSignature;
            assertThatThrownBy(() -> es.verify(token))
                .as("signature '" + badSignature + "'").isInstanceOf(UnauthorizedException.class);
        }
    }

    // ---- sessions ------------------------------------------------------------

    @Test
    void sessionsExpireAfterTheIdleTimeout() {
        AtomicLong clock = new AtomicLong();
        InMemorySessionStore store = new InMemorySessionStore(Duration.ofMinutes(30), 10, clock::get);
        store.create("a");

        clock.addAndGet(Duration.ofMinutes(29).toNanos());
        assertThat(store.find("a")).isNotNull();          // touching it slides the window
        clock.addAndGet(Duration.ofMinutes(29).toNanos());
        assertThat(store.find("a")).isNotNull();
        clock.addAndGet(Duration.ofMinutes(31).toNanos());
        assertThat(store.find("a")).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void sessionStoreIsBounded() {
        AtomicLong clock = new AtomicLong();
        InMemorySessionStore store = new InMemorySessionStore(Duration.ofMinutes(30), 3, clock::get);
        for (int i = 0; i < 50; i++) {
            clock.incrementAndGet();
            store.create("s" + i);
        }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.find("s49")).isNotNull();   // newest survive
        assertThat(store.find("s0")).isNull();       // oldest were evicted
    }

    @Test
    void regenerateIssuesANewIdAndKeepsAttributes() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionMiddleware middleware = SessionMiddleware.of(SECRET, store);
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("POST", "/login"), response, "/", null, null);

        String[] ids = new String[2];
        middleware.handle(ctx, () -> {
            Session before = ctx.attribute(SessionMiddleware.ATTRIBUTE);
            before.set("cart", 3);
            ids[0] = before.id();
            Session after = SessionMiddleware.regenerate(ctx);
            after.set("user", "ada");
            ids[1] = after.id();
        });

        assertThat(ids[1]).isNotEqualTo(ids[0]);
        assertThat(store.find(ids[0])).as("the pre-login id is dead").isNull();
        Session current = store.find(ids[1]);
        assertThat((Integer) current.get("cart")).isEqualTo(3);
        assertThat((String) current.get("user")).isEqualTo("ada");
    }

    @Test
    void invalidateDestroysTheSession() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionMiddleware middleware = SessionMiddleware.of(SECRET, store);
        Context ctx = new Context(FakeRequest.of("POST", "/logout"), new FakeResponse(), "/", null, null);

        String[] id = new String[1];
        middleware.handle(ctx, () -> {
            id[0] = ctx.<Session>attribute(SessionMiddleware.ATTRIBUTE).id();
            SessionMiddleware.invalidate(ctx);
        });

        assertThat(store.find(id[0])).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void secureCookieFlagIsOptIn() throws Exception {
        FakeResponse plain = new FakeResponse();
        SessionMiddleware.of(SECRET).handle(
            new Context(FakeRequest.of("GET", "/"), plain, "/", null, null), () -> { });
        assertThat(plain.headerValue("Set-Cookie")).contains("HttpOnly").doesNotContain("Secure");

        FakeResponse secure = new FakeResponse();
        SessionMiddleware.of(SECRET).secureCookie(true).handle(
            new Context(FakeRequest.of("GET", "/"), secure, "/", null, null), () -> { });
        assertThat(secure.headerValue("Set-Cookie")).contains("Secure").contains("HttpOnly");
    }
}
