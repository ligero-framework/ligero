package com.ligero.server.loom;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * A throwaway self-signed certificate for {@code localhost} / {@code 127.0.0.1},
 * created with the JDK's own {@code keytool}, in the three forms the tests need:
 * a PKCS#12 keystore, PEM files, and a client {@link SSLContext} that trusts it.
 */
final class TestCertificates {

    static final char[] PASSWORD = "changeit".toCharArray();

    final Path keyStore;
    final Path certificatePem;
    final Path privateKeyPem;
    final SSLContext clientContext;

    private TestCertificates(Path keyStore, Path certificatePem, Path privateKeyPem, SSLContext clientContext) {
        this.keyStore = keyStore;
        this.certificatePem = certificatePem;
        this.privateKeyPem = privateKeyPem;
        this.clientContext = clientContext;
    }

    static TestCertificates create(Path directory, String keyAlgorithm) throws Exception {
        Path keyStore = directory.resolve("server-" + keyAlgorithm + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server",
            "-keyalg", keyAlgorithm, "-keysize", keyAlgorithm.equals("EC") ? "256" : "2048",
            "-validity", "2", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", keyStore.toString(),
            "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, PASSWORD);
        }
        Certificate certificate = store.getCertificate("server");
        PrivateKey key = (PrivateKey) store.getKey("server", PASSWORD);

        Base64.Encoder pem = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Path certificatePem = directory.resolve("cert-" + keyAlgorithm + ".pem");
        Files.writeString(certificatePem, "-----BEGIN CERTIFICATE-----\n"
            + pem.encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        Path privateKeyPem = directory.resolve("key-" + keyAlgorithm + ".pem");
        Files.writeString(privateKeyPem, "-----BEGIN PRIVATE KEY-----\n"
            + pem.encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustManagers.getTrustManagers(), null);

        return new TestCertificates(keyStore, certificatePem, privateKeyPem, clientContext);
    }
}
