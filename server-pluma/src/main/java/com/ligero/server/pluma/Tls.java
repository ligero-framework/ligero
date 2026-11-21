package com.ligero.server.pluma;

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
 * HTTPS for the Pluma engine, using the JDK's own TLS implementation — no
 * extra dependency.
 *
 * <pre>{@code
 * // certificate chain + private key as PEM files (what Let's Encrypt and most CAs hand out)
 * Tls tls = Tls.fromPem(Path.of("fullchain.pem"), Path.of("privkey.pem"));
 * app.engine(new PlumaServerEngine(PlumaServerEngine.Limits.defaults(), tls));
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Tls.class);
    private static final long RELOAD_CHECK_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    /** A loaded certificate and the cipher suites offered with it. */
    private record Material(SSLContext context, String[] cipherSuites) {
    }

    /** Loads the certificate from wherever it lives. */
    private interface Source {
        SSLContext load() throws IOException, GeneralSecurityException;
    }

    private final Source source;         // null when the context was supplied ready-made
    private final Path[] watched;        // files whose change triggers a reload
    private volatile Material material;
    private volatile long lastModified;
    private volatile long nextCheckNanos;

    private Tls(SSLContext context, Source source, Path... watched) {
        this.material = new Material(context, safeCipherSuites(context));
        this.source = source;
        this.watched = watched;
        this.lastModified = stamp();
        this.nextCheckNanos = System.nanoTime() + RELOAD_CHECK_NANOS;
    }

    /** Uses an {@link SSLContext} you built yourself (custom key managers, HSM, ...). */
    public static Tls of(SSLContext context) {
        return new Tls(context, null);
    }

    /**
     * The certificate currently in force. Renewed certificate files (Let's
     * Encrypt replaces them every couple of months) are picked up without a
     * restart: the files are checked every few seconds and reloaded when they
     * change. A renewal that cannot be loaded — a half-written file, a key
     * that does not match — is logged and the previous certificate stays in
     * use, so a bad deploy cannot take HTTPS down.
     */
    private Material current() {
        if (source == null || System.nanoTime() - nextCheckNanos < 0) {
            return material;
        }
        synchronized (this) {
            if (System.nanoTime() - nextCheckNanos < 0) {
                return material;
            }
            nextCheckNanos = System.nanoTime() + RELOAD_CHECK_NANOS;
            long stamp = stamp();
            if (stamp != lastModified) {
                try {
                    SSLContext reloaded = source.load();
                    material = new Material(reloaded, safeCipherSuites(reloaded));
                    lastModified = stamp;
                    log.info("TLS certificate reloaded");
                } catch (IOException | GeneralSecurityException | RuntimeException e) {
                    log.warn("Could not reload the TLS certificate; keeping the current one: {}", e.toString());
                }
            }
            return material;
        }
    }

    /** Changes whenever one of the watched files does. */
    private long stamp() {
        long stamp = 0;
        for (Path file : watched) {
            try {
                stamp = stamp * 31 + Files.getLastModifiedTime(file).toMillis() + Files.size(file);
            } catch (IOException missing) {
                stamp = stamp * 31 - 1;
            }
        }
        return stamp;
    }

    /** Forces the next connection to check the certificate files (tests). */
    void checkNow() {
        nextCheckNanos = System.nanoTime();
    }

    /**
     * Certificate chain and private key from PEM files. The key must be an
     * unencrypted PKCS#8 key ({@code -----BEGIN PRIVATE KEY-----}), RSA or EC.
     */
    public static Tls fromPem(Path certificateChain, Path privateKey) {
        Source source = () -> pemContext(certificateChain, privateKey);
        try {
            return new Tls(source.load(), source, certificateChain, privateKey);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not load the TLS certificate/key: " + e.getMessage(), e);
        }
    }

    private static SSLContext pemContext(Path certificateChain, Path privateKey)
            throws IOException, GeneralSecurityException {
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
        return context(store, none);
    }

    /** Certificate and key from a PKCS#12 (or JKS) keystore file. */
    public static Tls fromKeyStore(Path keyStore, char[] password) {
        char[] secret = password.clone();
        Source source = () -> {
            try (InputStream in = Files.newInputStream(keyStore)) {
                KeyStore store = KeyStore.getInstance(
                    keyStore.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
                store.load(in, secret);
                return context(store, secret);
            }
        };
        try {
            return new Tls(source.load(), source, keyStore);
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

    private static SSLContext context(KeyStore store, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
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
        Material current = current();
        SSLSocket secure = (SSLSocket) current.context().getSocketFactory()
            .createSocket(socket, new ByteArrayInputStream(new byte[0]), true);
        secure.setUseClientMode(false);
        SSLParameters parameters = secure.getSSLParameters();
        parameters.setProtocols(PROTOCOLS);
        parameters.setCipherSuites(current.cipherSuites());
        parameters.setUseCipherSuitesOrder(true);
        parameters.setApplicationProtocols(applicationProtocols);
        secure.setSSLParameters(parameters);
        return secure;
    }
}
