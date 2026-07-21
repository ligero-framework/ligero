package com.ligero.auth;

import com.ligero.http.UnauthorizedException;
import com.ligero.json.JacksonBodyMapper;
import com.ligero.spi.BodyMapper;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAsymmetricTest {

    private final BodyMapper mapper = new JacksonBodyMapper();

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    @Test
    void rs256SignsAndVerifies() throws Exception {
        KeyPair keys = rsa();
        Jwt jwt = Jwt.rs256(keys.getPrivate(), keys.getPublic(), mapper);
        String token = jwt.sign(Map.of("sub", "ada"), Duration.ofHours(1));

        assertThat(jwt.verify(token)).containsEntry("sub", "ada");
        // a verify-only Jwt (resource server) validates the same token
        assertThat(Jwt.rs256Verifier(keys.getPublic(), mapper).verify(token))
            .containsEntry("sub", "ada");
    }

    @Test
    void es256SignsAndVerifies() throws Exception {
        KeyPair keys = ec();
        Jwt jwt = Jwt.es256(keys.getPrivate(), keys.getPublic(), mapper);
        String token = jwt.sign(Map.of("sub", "eve"), Duration.ofHours(1));
        assertThat(jwt.verify(token)).containsEntry("sub", "eve");
    }

    @Test
    void aTokenFromAnotherKeyIsRejected() throws Exception {
        String token = Jwt.rs256(rsa().getPrivate(), rsa().getPublic(), mapper)
            .sign(Map.of("sub", "x"), Duration.ofHours(1));
        // verify with an unrelated public key
        assertThatThrownBy(() -> Jwt.rs256Verifier(rsa().getPublic(), mapper).verify(token))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void algorithmConfusionIsRejected() throws Exception {
        // an HS256 token must not be accepted by an RS256 verifier
        String hsToken = Jwt.hs256("a-32-byte-minimum-secret-value!!", mapper)
            .sign(Map.of("sub", "x"), Duration.ofHours(1));
        assertThatThrownBy(() -> Jwt.rs256Verifier(rsa().getPublic(), mapper).verify(hsToken))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("algorithm");
    }

    @Test
    void jwksParsesRsaKeysAndVerifiesTokens() throws Exception {
        KeyPair keys = rsa();
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        String jwksJson = "{\"keys\":[{"
            + "\"kty\":\"RSA\",\"kid\":\"key-1\","
            + "\"n\":\"" + b64url(pub.getModulus()) + "\","
            + "\"e\":\"" + b64url(pub.getPublicExponent()) + "\"}]}";

        Jwks jwks = Jwks.parse(jwksJson, mapper);
        PublicKey resolved = jwks.key("key-1").orElseThrow();

        String token = Jwt.rs256(keys.getPrivate(), keys.getPublic(), mapper)
            .sign(Map.of("sub", "from-idp"), Duration.ofHours(1));
        assertThat(Jwt.rs256Verifier(resolved, mapper).verify(token))
            .containsEntry("sub", "from-idp");
        assertThat(jwks.key("missing")).isEmpty();
    }

    private static String b64url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) { // strip sign byte
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
