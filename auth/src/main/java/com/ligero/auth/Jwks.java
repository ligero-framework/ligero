package com.ligero.auth;

import com.ligero.spi.BodyMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a JSON Web Key Set (JWKS) — the public keys an OIDC provider publishes
 * at its {@code jwks_uri} — into JDK {@link PublicKey}s keyed by {@code kid}.
 * Fetch the JWKS document yourself (e.g. with {@code java.net.http.HttpClient})
 * and hand the body here; the JSON is parsed through the app's {@link BodyMapper}
 * so no extra dependency is pulled in.
 *
 * <pre>{@code
 * Jwks jwks = Jwks.parse(httpBody, bodyMapper);
 * PublicKey key = jwks.key(kidFromTokenHeader).orElseThrow();
 * Map<String, Object> claims = Jwt.rs256Verifier(key, bodyMapper).verify(token);
 * }</pre>
 *
 * <p>Supports RSA keys ({@code kty=RSA}) and EC P-256 keys ({@code kty=EC},
 * {@code crv=P-256}) — the key types behind RS256 and ES256.</p>
 */
public final class Jwks {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private final Map<String, PublicKey> keys;

    private Jwks(Map<String, PublicKey> keys) {
        this.keys = keys;
    }

    /** The public key for a {@code kid}, if present. */
    public Optional<PublicKey> key(String kid) {
        return Optional.ofNullable(keys.get(kid));
    }

    /** All parsed keys, keyed by {@code kid}. */
    public Map<String, PublicKey> keys() {
        return Map.copyOf(keys);
    }

    @SuppressWarnings("unchecked")
    public static Jwks parse(String json, BodyMapper mapper) {
        Map<String, Object> document = mapper.readJson(json, Map.class);
        Object rawKeys = document.get("keys");
        if (!(rawKeys instanceof List<?> list)) {
            throw new IllegalArgumentException("JWKS has no \"keys\" array");
        }
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> jwk)) {
                continue;
            }
            Map<String, Object> key = (Map<String, Object>) jwk;
            String kid = String.valueOf(key.get("kid"));
            try {
                PublicKey publicKey = toKey(key);
                if (publicKey != null) {
                    parsed.put(kid, publicKey);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Unparseable JWK (kid=" + kid + ")", e);
            }
        }
        return new Jwks(parsed);
    }

    private static PublicKey toKey(Map<String, Object> jwk) throws Exception {
        String kty = String.valueOf(jwk.get("kty"));
        return switch (kty) {
            case "RSA" -> {
                BigInteger modulus = positive(jwk.get("n"));
                BigInteger exponent = positive(jwk.get("e"));
                yield KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            }
            case "EC" -> {
                if (!"P-256".equals(String.valueOf(jwk.get("crv")))) {
                    yield null; // only P-256 (ES256) is supported
                }
                ECPoint point = new ECPoint(positive(jwk.get("x")), positive(jwk.get("y")));
                yield KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(point, P256.params()));
            }
            default -> null;
        };
    }

    private static BigInteger positive(Object base64Url) {
        byte[] bytes = B64URL.decode(String.valueOf(base64Url).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, bytes);
    }
}
