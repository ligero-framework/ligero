package com.ligero.server.loom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * HTTPS for the Loom engine, using the JDK's own TLS implementation — no
 * extra dependency.
 *
 * <pre>{@code
 * // certificate chain + private key as PEM files (what Let's Encrypt and most CAs hand out)
 * Tls tls = Tls.fromPem(Path.of("fullchain.pem"), Path.of("privkey.pem"));
 * app.engine(new LoomServerEngine(LoomServerEngine.Limits.defaults(), tls));
 * }</pre>
 *
 * <p>Or with no code at all: set {@code LIGERO_TLS_CERT} and
 * {@code LIGERO_TLS_KEY} (PEM files), or {@code LIGERO_TLS_KEYSTORE} and
 * {@code LIGERO_TLS_KEYSTORE_PASSWORD} (PKCS#12), and the engine picks them
 * up.</p>
 *
 * <p>Only what is considered safe today is offered to clients: TLS 1.3 and
 * TLS 1.2, and for TLS 1.2 only forward-secret key exchange (ECDHE) with
 * authenticated encryption (AES-GCM, ChaCha20-Poly1305). Older protocol
 * versions and CBC or static-RSA cipher suites are never negotiated.</p>
 */
public final class Tls {

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SSLContext context;
    private final String[] cipherSuites;

    private Tls(SSLContext context) {
        this.context = context;
        this.cipherSuites = safeCipherSuites(context);
    }

    /** Uses an {@link SSLContext} you built yourself (custom key managers, HSM, ...). */
    public static Tls of(SSLContext context) {
        return new Tls(context);
    }

    /**
     * Certificate chain and private key from PEM files. The key must be an
     * unencrypted PKCS#8 key ({@code -----BEGIN PRIVATE KEY-----}), RSA or EC.
     */
    public static Tls fromPem(Path certificateChain, Path privateKey) {
        try {
            List<Certificate> chain = new ArrayList<>();
            try (InputStream in = Files.newInputStream(certificateChain)) {
                chain.addAll(CertificateFactory.getInstance("X.509").generateCertificates(in));
            }
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("No certificate found in " + certificateChain);
            }
            PrivateKey key = readPrivateKey(Files.readString(privateKey, StandardCharsets.US_ASCII), privateKey);
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            char[] none = new char[0];
            store.setKeyEntry("ligero", key, none, chain.toArray(new Certificate[0]));
            return fromStore(store, none);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not load the TLS certificate/key: " + e.getMessage(), e);
        }
    }

    /** Certificate and key from a PKCS#12 (or JKS) keystore file. */
    public static Tls fromKeyStore(Path keyStore, char[] password) {
        try (InputStream in = Files.newInputStream(keyStore)) {
            KeyStore store = KeyStore.getInstance(
                keyStore.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
            store.load(in, password);
            return fromStore(store, password);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not load the TLS keystore: " + e.getMessage(), e);
        }
    }

    /**
     * TLS settings from {@code LIGERO_TLS_*} environment variables, or
     * {@code null} when none are set (plain HTTP).
     */
    public static Tls fromEnvironment(Map<String, String> env) {
        String cert = env.get("LIGERO_TLS_CERT");
        String key = env.get("LIGERO_TLS_KEY");
        String keyStore = env.get("LIGERO_TLS_KEYSTORE");
        if (isSet(cert) || isSet(key)) {
            if (!isSet(cert) || !isSet(key)) {
                throw new IllegalArgumentException("Set both LIGERO_TLS_CERT and LIGERO_TLS_KEY");
            }
            return fromPem(Path.of(cert), Path.of(key));
        }
        if (isSet(keyStore)) {
            String password = env.getOrDefault("LIGERO_TLS_KEYSTORE_PASSWORD", "");
            return fromKeyStore(Path.of(keyStore), password.toCharArray());
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static Tls fromStore(KeyStore store, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return new Tls(context);
    }

    private static PrivateKey readPrivateKey(String pem, Path source) throws GeneralSecurityException {
        if (pem.contains("ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException(source + " is password-protected; provide an unencrypted key "
                + "(openssl pkcs8 -in key.pem -out plain.pem -nocrypt) or use a PKCS#12 keystore");
        }
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < 0) {
            throw new IllegalArgumentException(source + " is not a PKCS#8 private key. Convert it with: "
                + "openssl pkcs8 -topk8 -nocrypt -in " + source.getFileName() + " -out privkey-pkcs8.pem");
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException notThisAlgorithm) {
                // try the next one
            }
        }
        throw new IllegalArgumentException("Unsupported private key type in " + source + " (RSA and EC are supported)");
    }

    /** TLS 1.3 suites, plus TLS 1.2 suites with ECDHE and AEAD only. */
    private static String[] safeCipherSuites(SSLContext context) {
        List<String> safe = new ArrayList<>();
        for (String suite : context.getSupportedSSLParameters().getCipherSuites()) {
            boolean tls13 = suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_");
            boolean tls12 = suite.startsWith("TLS_ECDHE_")
                && (suite.contains("_GCM_") || suite.contains("CHACHA20_POLY1305"));
            if (tls13 || tls12) {
                safe.add(suite);
            }
        }
        if (safe.isEmpty()) {
            throw new IllegalStateException("This JVM offers no TLS cipher suite considered safe");
        }
        return safe.toArray(new String[0]);
    }

    /**
     * Wraps an accepted socket for the server side of a handshake. The
     * handshake itself happens on first use, on the connection's own thread.
     *
     * @param applicationProtocols ALPN protocols offered, most preferred first
     */
    SSLSocket wrap(Socket socket, String... applicationProtocols) throws IOException {
        SSLSocket secure = (SSLSocket) context.getSocketFactory()
            .createSocket(socket, new ByteArrayInputStream(new byte[0]), true);
        secure.setUseClientMode(false);
        SSLParameters parameters = secure.getSSLParameters();
        parameters.setProtocols(PROTOCOLS);
        parameters.setCipherSuites(cipherSuites);
        parameters.setUseCipherSuitesOrder(true);
        parameters.setApplicationProtocols(applicationProtocols);
        secure.setSSLParameters(parameters);
        return secure;
    }
}
