package com.ligero.auth;

import com.ligero.http.UnauthorizedException;
import com.ligero.spi.BodyMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT signing and verification with {@code exp}/{@code nbf} enforcement.
 * Supports the symmetric <b>HS256</b> and the asymmetric <b>RS256</b> and
 * <b>ES256</b> algorithms — the latter two let you verify tokens issued by an
 * external identity provider (OIDC) against its public keys (see {@link Jwks}).
 *
 * <pre>{@code
 * // Symmetric (issue + verify yourself)
 * Jwt jwt = Jwt.hs256(secret, bodyMapper);
 * String token = jwt.sign(Map.of("sub", "ada"), Duration.ofHours(1));
 * Map<String, Object> claims = jwt.verify(token);
 *
 * // Resource server: verify tokens from an IdP with its RSA public key
 * Jwt verifier = Jwt.rs256Verifier(idpPublicKey, bodyMapper);
 * Map<String, Object> claims = verifier.verify(bearerToken);
 * }</pre>
 */
public final class Jwt {

    /** Supported signature algorithms. */
    public enum Algorithm {
        HS256("HS256"), RS256("RS256"), ES256("ES256");

        final String jose;

        Algorithm(String jose) {
            this.jose = jose;
        }
    }

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final Algorithm algorithm;
    private final byte[] secret;         // HS256 only
    private final PrivateKey privateKey; // RS256/ES256 signing (null = verify-only)
    private final PublicKey publicKey;   // RS256/ES256 verification
    private final BodyMapper mapper;
    private final String headerB64;

    private Jwt(Algorithm algorithm, byte[] secret, PrivateKey privateKey,
                PublicKey publicKey, BodyMapper mapper) {
        this.algorithm = algorithm;
        this.secret = secret == null ? null : secret.clone();
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.mapper = mapper;
        this.headerB64 = B64E.encodeToString(
            ("{\"alg\":\"" + algorithm.jose + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
    }

    // ---- factories ----------------------------------------------------------

    public static Jwt hs256(String secret, BodyMapper mapper) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("HS256 secret must be at least 32 bytes");
        }
        return new Jwt(Algorithm.HS256, bytes, null, null, mapper);
    }

    /** RS256 signer + verifier (you issue and verify tokens). */
    public static Jwt rs256(PrivateKey signer, PublicKey verifier, BodyMapper mapper) {
        return new Jwt(Algorithm.RS256, null, signer, verifier, mapper);
    }

    /** RS256 verify-only (resource server validating an IdP's tokens). */
    public static Jwt rs256Verifier(PublicKey verifier, BodyMapper mapper) {
        return new Jwt(Algorithm.RS256, null, null, verifier, mapper);
    }

    /** ES256 signer + verifier. */
    public static Jwt es256(PrivateKey signer, PublicKey verifier, BodyMapper mapper) {
        return new Jwt(Algorithm.ES256, null, signer, verifier, mapper);
    }

    /** ES256 verify-only. */
    public static Jwt es256Verifier(PublicKey verifier, BodyMapper mapper) {
        return new Jwt(Algorithm.ES256, null, null, verifier, mapper);
    }

    // ---- sign / verify ------------------------------------------------------

    /** Signs the claims, adding {@code iat} and {@code exp}. */
    public String sign(Map<String, Object> claims, Duration ttl) {
        if (algorithm != Algorithm.HS256 && privateKey == null) {
            throw new IllegalStateException("This Jwt is verify-only (no private key)");
        }
        Map<String, Object> payload = new HashMap<>(claims);
        long now = Instant.now().getEpochSecond();
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());
        String body = B64E.encodeToString(mapper.writeJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + body;
        return signingInput + "." + B64E.encodeToString(signature(signingInput));
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
        // pin the algorithm (defends against alg-confusion and "none" attacks)
        if (!header.contains("\"" + algorithm.jose + "\"")) {
            throw new UnauthorizedException("Unsupported token algorithm");
        }
        if (!verifySignature(parts[0] + "." + parts[1], signature)) {
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

    private byte[] signature(String signingInput) {
        byte[] input = signingInput.getBytes(StandardCharsets.UTF_8);
        try {
            return switch (algorithm) {
                case HS256 -> hmac(input);
                case RS256 -> {
                    Signature s = Signature.getInstance("SHA256withRSA");
                    s.initSign(privateKey);
                    s.update(input);
                    yield s.sign();
                }
                case ES256 -> {
                    Signature s = Signature.getInstance("SHA256withECDSA");
                    s.initSign(privateKey);
                    s.update(input);
                    yield Ecdsa.derToJose(s.sign(), 64); // P-256 -> 64-byte R||S
                }
            };
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    private boolean verifySignature(String signingInput, byte[] signature) {
        byte[] input = signingInput.getBytes(StandardCharsets.UTF_8);
        try {
            return switch (algorithm) {
                case HS256 -> MessageDigest.isEqual(hmac(input), signature);
                case RS256 -> {
                    Signature s = Signature.getInstance("SHA256withRSA");
                    s.initVerify(publicKey);
                    s.update(input);
                    yield s.verify(signature);
                }
                case ES256 -> {
                    Signature s = Signature.getInstance("SHA256withECDSA");
                    s.initVerify(publicKey);
                    s.update(input);
                    yield s.verify(Ecdsa.joseToDer(signature));
                }
            };
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private byte[] hmac(byte[] input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input);
    }
}
