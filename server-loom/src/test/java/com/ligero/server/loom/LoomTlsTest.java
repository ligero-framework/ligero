package com.ligero.server.loom;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTPS on the Loom engine: certificate loading, negotiated parameters and what is refused. */
class LoomTlsTest {

    @TempDir
    static Path directory;

    private static TestCertificates rsa;
    private static TestCertificates ec;

    private Ligero app;
    private LoomServerEngine engine;

    @BeforeAll
    static void createCertificates() throws Exception {
        rsa = TestCertificates.create(directory, "RSA");
        ec = TestCertificates.create(directory, "EC");
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private String start(Tls tls) throws IOException {
        return start(tls, LoomServerEngine.Limits.defaults());
    }

    private String start(Tls tls, LoomServerEngine.Limits limits) throws IOException {
        app = Ligero.create(LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0).build());
        engine = new LoomServerEngine(limits, tls);
        app.engine(engine);
        app.get("/hello", ctx -> ctx.text("secure hello"));
        app.post("/echo", ctx -> ctx.text(ctx.bodyAsString()));
        app.start();
        return "https://127.0.0.1:" + app.port();
    }

    private static HttpClient client(TestCertificates certificates) {
        return HttpClient.newBuilder().sslContext(certificates.clientContext)
            .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Test
    void servesHttpsFromPemFiles() throws Exception {
        String base = start(Tls.fromPem(rsa.certificatePem, rsa.privateKeyPem));

        HttpResponse<String> response = client(rsa).send(
            HttpRequest.newBuilder(URI.create(base + "/hello")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("secure hello");
        assertThat(response.sslSession()).isPresent();
        assertThat(response.sslSession().get().getProtocol()).isEqualTo("TLSv1.3");
    }

    @Test
    void servesHttpsFromAKeyStoreWithAnEcKeyAndKeepsConnectionsAlive() throws Exception {
        String base = start(Tls.fromKeyStore(ec.keyStore, TestCertificates.PASSWORD));
        HttpClient client = client(ec);

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("payload " + i)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).isEqualTo("payload " + i);
        }
        assertThat(engine.openConnections()).as("requests reused one TLS connection").isEqualTo(1);
    }

    @Test
    void readsSettingsFromTheEnvironment() throws Exception {
        assertThat(Tls.fromEnvironment(Map.of())).isNull();

        String base = start(Tls.fromEnvironment(Map.of(
            "LIGERO_TLS_CERT", rsa.certificatePem.toString(),
            "LIGERO_TLS_KEY", rsa.privateKeyPem.toString())));
        assertThat(client(rsa).send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("secure hello");
        app.stop();

        base = start(Tls.fromEnvironment(Map.of(
            "LIGERO_TLS_KEYSTORE", ec.keyStore.toString(),
            "LIGERO_TLS_KEYSTORE_PASSWORD", new String(TestCertificates.PASSWORD))));
        assertThat(client(ec).send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("secure hello");

        assertThatThrownBy(() -> Tls.fromEnvironment(Map.of("LIGERO_TLS_CERT", "only-one.pem")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LIGERO_TLS_KEY");
    }

    @Test
    void negotiatesTls12WithForwardSecrecyButNothingOlder() throws Exception {
        start(Tls.fromPem(rsa.certificatePem, rsa.privateKeyPem));

        try (SSLSocket socket = connect(rsa.clientContext, "TLSv1.2")) {
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.2");
            assertThat(socket.getSession().getCipherSuite()).startsWith("TLS_ECDHE_")
                .matches(suite -> suite.contains("_GCM_") || suite.contains("CHACHA20"));
        }

        // a client limited to a static-RSA / CBC suite finds nothing in common
        try (SSLSocket socket = connect(rsa.clientContext, "TLSv1.2")) {
            SSLParameters weak = socket.getSSLParameters();
            weak.setCipherSuites(new String[] {"TLS_RSA_WITH_AES_128_CBC_SHA"});
            socket.setSSLParameters(weak);
            assertThatThrownBy(socket::startHandshake).isInstanceOf(IOException.class);
        }
    }

    private SSLSocket connect(SSLContext context, String protocol) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", app.port());
        socket.setSoTimeout(5000);
        socket.setEnabledProtocols(new String[] {protocol});
        return socket;
    }

    @Test
    void untrustedCertificatesAndPlainHttpAreNotServed() throws Exception {
        String base = start(Tls.fromPem(rsa.certificatePem, rsa.privateKeyPem));

        // a client that does not trust the certificate refuses it
        assertThatThrownBy(() -> client(ec).send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);

        // plain HTTP sent to the TLS port gets no HTTP answer at all
        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] answer = socket.getInputStream().readAllBytes();
            assertThat(new String(answer, StandardCharsets.ISO_8859_1)).doesNotContain("secure hello")
                .doesNotContain("HTTP/1.1 200");
        }
        // and the server is unaffected
        assertThat(client(rsa).send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("secure hello");
    }

    @Test
    void stalledHandshakesAreCutOff() throws Exception {
        start(Tls.fromPem(rsa.certificatePem, rsa.privateKeyPem),
            LoomServerEngine.Limits.defaults().withHeaderTimeout(Duration.ofMillis(300)));

        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            // connect and say nothing: the handshake never starts
            long begin = System.nanoTime();
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void unusableKeyFilesAreExplained() throws Exception {
        Path legacy = directory.resolve("legacy.pem");
        Files.writeString(legacy, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        assertThatThrownBy(() -> Tls.fromPem(rsa.certificatePem, legacy))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openssl pkcs8 -topk8");

        Path encrypted = directory.resolve("encrypted.pem");
        Files.writeString(encrypted, "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");
        assertThatThrownBy(() -> Tls.fromPem(rsa.certificatePem, encrypted))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password-protected");

        assertThatThrownBy(() -> Tls.fromKeyStore(rsa.keyStore, "wrong".toCharArray()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renewedCertificatesAreServedWithoutARestart() throws Exception {
        Path cert = directory.resolve("live-cert.pem");
        Path key = directory.resolve("live-key.pem");
        Files.copy(rsa.certificatePem, cert, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rsa.privateKeyPem, key, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Tls tls = Tls.fromPem(cert, key);
        String base = start(tls);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/hello")).build();

        assertThat(client(rsa).send(request, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("secure hello");

        // a broken renewal (key that does not belong to the certificate file's format) is ignored
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        tls.checkNow();
        assertThat(client(rsa).send(request, HttpResponse.BodyHandlers.ofString()).body())
            .as("the previous certificate stays in force").isEqualTo("secure hello");

        // the real renewal: new certificate and key on disk
        Files.copy(ec.certificatePem, cert, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ec.privateKeyPem, key, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tls.checkNow();
        assertThat(client(ec).send(request, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("secure hello");
        // and a client that only trusts the old certificate is now refused
        assertThatThrownBy(() -> client(rsa).send(request, HttpResponse.BodyHandlers.ofString()))
            .isInstanceOf(SSLHandshakeException.class);
    }
}
