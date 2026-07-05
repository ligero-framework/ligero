package com.ligero.auth;

import com.ligero.http.UnauthorizedException;
import com.ligero.spi.BodyMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal JWT support: HS256 signing and verification with {@code exp}/
 * {@code nbf} enforcement. Asymmetric algorithms are intentionally out of
 * scope — delegate to a dedicated library if you need them.
 *
 * <pre>{@code
 * Jwt jwt = Jwt.hs256(secret, bodyMapper);
 * String token = jwt.sign(Map.of("sub", "ada", "roles", List.of("admin")), Duration.ofHours(1));
 * Map<String, Object> claims = jwt.verify(token); // throws UnauthorizedException if invalid
 * }</pre>
 */
public final class Jwt {

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER_B64 =
        B64E.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final BodyMapper mapper;

    private Jwt(byte[] secret, BodyMapper mapper) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("HS256 secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
        this.mapper = mapper;
    }

    public static Jwt hs256(String secret, BodyMapper mapper) {
        return new Jwt(secret.getBytes(StandardCharsets.UTF_8), mapper);
    }

    /** Signs the claims, adding {@code iat} and {@code exp}. */
    public String sign(Map<String, Object> claims, Duration ttl) {
        Map<String, Object> payload = new HashMap<>(claims);
        long now = Instant.now().getEpochSecond();
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());
        String body = B64E.encodeToString(mapper.writeJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER_B64 + "." + body;
        return signingInput + "." + B64E.encodeToString(hmac(signingInput));
    }

    /**
     * Verifies signature and time claims and returns the payload.
     *
     * @throws UnauthorizedException on any verification failure
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new UnauthorizedException("Malformed token");
        }
        String header;
        byte[] signature;
        try {
            header = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
            signature = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Malformed token");
        }
        // reject anything that isn't exactly HS256 (alg confusion / "none" attacks)
        if (!header.contains("\"HS256\"")) {
            throw new UnauthorizedException("Unsupported token algorithm");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected, signature)) {
            throw new UnauthorizedException("Invalid token signature");
        }
        Map<String, Object> claims;
        try {
            claims = mapper.readJson(
                new String(B64D.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        } catch (RuntimeException e) {
            throw new UnauthorizedException("Malformed token payload");
        }
        long now = Instant.now().getEpochSecond();
        if (claims.get("exp") instanceof Number exp && now >= exp.longValue()) {
            throw new UnauthorizedException("Token expired");
        }
        if (claims.get("nbf") instanceof Number nbf && now < nbf.longValue()) {
            throw new UnauthorizedException("Token not yet valid");
        }
        return claims;
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
