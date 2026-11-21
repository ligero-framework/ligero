package com.ligero.server.pluma;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP/2 on the Pluma engine. Real clients (the JDK's, over TLS with ALPN)
 * cover ordinary use; hand-built frames over a plain socket cover the rules
 * and the attacks a server must withstand.
 */
class PlumaHttp2Test {

    @TempDir
    static Path directory;

    private static TestCertificates certificates;

    private Ligero app;
    private PlumaServerEngine engine;

    @BeforeAll
    static void createCertificate() throws Exception {
        certificates = TestCertificates.create(directory, "EC");
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private static LigeroConfig.Builder config() {
        return LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0);
    }

    private Ligero newApp(LigeroConfig config) {
        Ligero created = Ligero.create(config);
        created.get("/hello", ctx -> ctx.text("hello over " + ctx.req().getProtocol()));
        created.get("/items/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParamAsLong("id"))));
        created.post("/echo", ctx -> ctx.text(ctx.bodyAsString()));
        created.get("/empty", ctx -> ctx.status(204).res().end());
        created.get("/host", ctx -> ctx.text(String.valueOf(ctx.header("Host")) + "|" + ctx.header("cookie")));
        return created;
    }

    /** HTTPS, so clients negotiate HTTP/2 through ALPN. */
    private String startTls(Ligero app, PlumaServerEngine.Limits limits) throws IOException {
        this.app = app;
        this.engine = new PlumaServerEngine(limits, Tls.fromKeyStore(certificates.keyStore, TestCertificates.PASSWORD));
        app.engine(engine);
        app.start();
        return "https://127.0.0.1:" + app.port();
    }

    /** Plain TCP: HTTP/2 with prior knowledge, for the raw-frame tests. */
    private void startPlain(Ligero app, PlumaServerEngine.Limits limits) throws IOException {
        this.app = app;
        this.engine = new PlumaServerEngine(limits, null);
        app.engine(engine);
        app.start();
    }

    private HttpClient http2Client() {
        return HttpClient.newBuilder().sslContext(certificates.clientContext)
            .version(HttpClient.Version.HTTP_2).build();
    }

    // ------------------------------------------------------------------ real clients

    @Test
    void servesHttp2OverTlsAndMultiplexesOnOneConnection() throws Exception {
        String base = startTls(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        HttpClient client = http2Client();

        HttpResponse<String> hello = client.send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(hello.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(hello.body()).isEqualTo("hello over HTTP/2.0");
        assertThat(hello.headers().firstValue("x-content-type-options")).contains("nosniff");

        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (int i = 0; i < 80; i++) { // below MAX_CONCURRENT_STREAMS: the JDK client fails instead of queueing
            pending.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/items/" + i)).build(),
                HttpResponse.BodyHandlers.ofString()));
        }
        for (int i = 0; i < pending.size(); i++) {
            assertThat(pending.get(i).get(20, TimeUnit.SECONDS).body()).isEqualTo("{\"id\":" + i + "}");
        }
        assertThat(engine.openConnections()).as("every request shared one connection").isEqualTo(1);

        HttpResponse<String> echo = client.send(HttpRequest.newBuilder(URI.create(base + "/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("cuerpo de prueba")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(echo.body()).isEqualTo("cuerpo de prueba");

        HttpResponse<String> empty = client.send(HttpRequest.newBuilder(URI.create(base + "/empty")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(empty.statusCode()).isEqualTo(204);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/missing")).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/items/abc")).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void http11ClientsStillWorkOnTheSameTlsPort() throws Exception {
        String base = startTls(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        HttpClient http11 = HttpClient.newBuilder().sslContext(certificates.clientContext)
            .version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<String> response = http11.send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(response.body()).isEqualTo("hello over HTTP/1.1");
    }

    @Test
    void largeBodiesFlowInBothDirections() throws Exception {
        byte[] large = new byte[3_000_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i * 31);
        }
        Ligero created = newApp(config().maxBodyBytes(4L * 1024 * 1024).build());
        created.get("/download", ctx -> {
            try (OutputStream out = ctx.res().contentType("application/octet-stream").getOutputStream()) {
                out.write(large);
            }
        });
        created.put("/upload", ctx -> {
            long sum = 0;
            long total = 0;
            byte[] chunk = new byte[8192];
            try (InputStream in = ctx.bodyStream()) {
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    total += n;
                    for (int i = 0; i < n; i++) {
                        sum += chunk[i];
                    }
                }
            }
            ctx.text(total + ":" + sum);
        });
        created.put("/buffered", ctx -> ctx.text(String.valueOf(ctx.bodyAsBytes().length)));
        String base = startTls(created, PlumaServerEngine.Limits.defaults());
        HttpClient client = http2Client();

        HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(URI.create(base + "/download")).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(download.body()).isEqualTo(large);

        long expectedSum = 0;
        for (byte b : large) {
            expectedSum += b;
        }
        HttpResponse<String> upload = client.send(HttpRequest.newBuilder(URI.create(base + "/upload"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(large)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(upload.body()).isEqualTo(large.length + ":" + expectedSum);

        HttpResponse<String> buffered = client.send(HttpRequest.newBuilder(URI.create(base + "/buffered"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(large)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(buffered.body()).isEqualTo(String.valueOf(large.length));

        // over the configured limit: 413, and the connection keeps working
        HttpResponse<String> tooBig = client.send(HttpRequest.newBuilder(URI.create(base + "/buffered"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[5_000_000])).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(tooBig.statusCode()).isEqualTo(413);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void streamsEventsAndCompressesOverHttp2() throws Exception {
        Ligero created = newApp(config().gzip(true).gzipMinBytes(10).build());
        created.get("/events", ctx -> {
            try (var sse = ctx.sse()) {
                sse.send("tick", "1");
                sse.send("tick", "2");
            }
        });
        created.get("/text", ctx -> ctx.text("ligero ".repeat(300)));
        String base = startTls(created, PlumaServerEngine.Limits.defaults());
        HttpClient client = http2Client();

        HttpResponse<String> events = client.send(HttpRequest.newBuilder(URI.create(base + "/events")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(events.body()).isEqualTo("event: tick\ndata: 1\n\nevent: tick\ndata: 2\n\n");

        HttpResponse<byte[]> text = client.send(HttpRequest.newBuilder(URI.create(base + "/text"))
            .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(text.headers().firstValue("content-encoding")).contains("gzip");
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(text.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ligero ".repeat(300));
        }
    }

    // ------------------------------------------------------------------ raw frames

    private static final int DATA = 0;
    private static final int HEADERS = 1;
    private static final int RST_STREAM = 3;
    private static final int SETTINGS = 4;
    private static final int PING = 6;
    private static final int GOAWAY = 7;
    private static final int WINDOW_UPDATE = 8;
    private static final int CONTINUATION = 9;

    private record Frame(int type, int flags, int streamId, byte[] payload) {
        int errorCode() {
            int offset = type == GOAWAY ? 4 : 0;
            return ((payload[offset] & 0xff) << 24) | ((payload[offset + 1] & 0xff) << 16)
                | ((payload[offset + 2] & 0xff) << 8) | (payload[offset + 3] & 0xff);
        }
    }

    /** A minimal HTTP/2 client that speaks in frames. */
    private final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private final Hpack.Decoder decoder = new Hpack.Decoder(4096, 64 * 1024);

        RawClient() throws IOException {
            this(new byte[0]);
        }

        RawClient(byte[] settingsPayload) throws IOException {
            socket = new Socket("127.0.0.1", app.port());
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            out.write(Http2Connection.PREFACE);
            send(SETTINGS, 0, 0, settingsPayload);
        }

        void send(int type, int flags, int streamId, byte[] payload) throws IOException {
            sendWithLength(type, flags, streamId, payload, payload.length);
        }

        void sendWithLength(int type, int flags, int streamId, byte[] payload, int declaredLength) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(declaredLength >>> 16);
            frame.write(declaredLength >>> 8);
            frame.write(declaredLength);
            frame.write(type);
            frame.write(flags);
            frame.write(streamId >>> 24);
            frame.write(streamId >>> 16);
            frame.write(streamId >>> 8);
            frame.write(streamId);
            frame.writeBytes(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        /** HEADERS for a GET, with whatever extra fields; END_HEADERS and END_STREAM set. */
        void get(int streamId, String path, String... extra) throws IOException {
            send(HEADERS, 0x4 | 0x1, streamId, headerBlock("GET", path, extra));
        }

        byte[] headerBlock(String method, String path, String... extra) {
            Hpack.Encoder encoder = new Hpack.Encoder();
            encoder.field(":method", method);
            encoder.field(":scheme", "http");
            if (path != null) {
                encoder.field(":path", path);
            }
            encoder.field(":authority", "example.test");
            for (int i = 0; i < extra.length; i += 2) {
                encoder.field(extra[i], extra[i + 1]);
            }
            return encoder.toByteArray();
        }

        Frame read() throws IOException {
            int length = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            int type = in.readUnsignedByte();
            int flags = in.readUnsignedByte();
            int streamId = in.readInt() & 0x7fffffff;
            byte[] payload = new byte[length];
            in.readFully(payload);
            return new Frame(type, flags, streamId, payload);
        }

        /** Next frame of the given type, skipping others (SETTINGS, acks, WINDOW_UPDATE...). */
        Frame next(int type) throws IOException {
            while (true) {
                Frame frame = read();
                if (frame.type() == type) {
                    return frame;
                }
            }
        }

        /** Next frame of the given type on the given stream. */
        Frame next(int type, int streamId) throws IOException {
            while (true) {
                Frame frame = read();
                if (frame.type() == type && frame.streamId() == streamId) {
                    return frame;
                }
            }
        }

        Map<String, String> responseHeaders(Frame headers) throws Exception {
            Map<String, String> map = new java.util.LinkedHashMap<>();
            for (Hpack.Field field : decoder.decode(headers.payload(), 0, headers.payload().length)) {
                map.put(field.name(), field.value());
            }
            return map;
        }

        /** True when the server has closed the connection. */
        boolean closedByServer() throws IOException {
            try {
                while (true) {
                    read();
                }
            } catch (EOFException | java.net.SocketException closed) {
                return true;
            } catch (SocketTimeoutException stillOpen) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    @Test
    void speaksHttp2OverPlainTcpWithPriorKnowledge() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            Frame settings = client.next(SETTINGS);
            assertThat(settings.flags() & 0x1).as("the server's own SETTINGS, not an ack").isZero();

            client.get(1, "/hello");
            Frame headers = client.next(HEADERS, 1);
            Map<String, String> fields = client.responseHeaders(headers);
            assertThat(fields).containsEntry(":status", "200").containsKey("date").containsKey("content-length");
            assertThat(fields.get("content-type")).startsWith("text/plain");
            Frame data = client.next(DATA, 1);
            assertThat(new String(data.payload(), StandardCharsets.UTF_8)).isEqualTo("hello over HTTP/2.0");
            assertThat(data.flags() & 0x1).as("END_STREAM").isEqualTo(1);

            // :authority is what applications see as Host; split cookie fields are joined
            client.get(3, "/host", "cookie", "a=1", "cookie", "b=2");
            client.next(HEADERS, 3);
            assertThat(new String(client.next(DATA, 3).payload(), StandardCharsets.UTF_8))
                .isEqualTo("example.test|a=1; b=2");

            // PING is echoed with the ACK flag
            client.send(PING, 0, 0, "12345678".getBytes(StandardCharsets.US_ASCII));
            Frame pong = client.next(PING);
            assertThat(pong.flags() & 0x1).isEqualTo(1);
            assertThat(new String(pong.payload(), StandardCharsets.US_ASCII)).isEqualTo("12345678");
        }
    }

    @Test
    void decodesHuffmanCodedAndContinuedHeaderBlocks() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            // :path as a Huffman-coded literal, the way browsers send it
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            block.write(0x82);                                   // :method GET   (static index 2)
            block.write(0x86);                                   // :scheme http  (static index 6)
            block.write(0x04);                                   // literal, name = static index 4 (:path)
            byte[] path = Hpack.huffmanEncode("/items/77");
            block.write(0x80 | path.length);
            block.writeBytes(path);
            block.write(0x41);                                   // literal with indexing, name = :authority
            byte[] authority = Hpack.huffmanEncode("example.test");
            block.write(0x80 | authority.length);
            block.writeBytes(authority);
            byte[] bytes = block.toByteArray();

            // split across HEADERS + CONTINUATION
            int half = bytes.length / 2;
            client.send(HEADERS, 0x1, 1, java.util.Arrays.copyOfRange(bytes, 0, half));
            client.send(CONTINUATION, 0x4, 1, java.util.Arrays.copyOfRange(bytes, half, bytes.length));

            assertThat(client.responseHeaders(client.next(HEADERS, 1))).containsEntry(":status", "200");
            assertThat(new String(client.next(DATA, 1).payload(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":77}");

            // the dynamic table entry added above (:authority example.test) can now be referenced by index 62
            ByteArrayOutputStream second = new ByteArrayOutputStream();
            second.write(0x82);
            second.write(0x86);
            second.write(0x04);
            second.write(5);
            second.writeBytes("/host".getBytes(StandardCharsets.US_ASCII));
            second.write(0x80 | 62);
            client.send(HEADERS, 0x4 | 0x1, 3, second.toByteArray());
            client.next(HEADERS, 3);
            assertThat(new String(client.next(DATA, 3).payload(), StandardCharsets.UTF_8)).startsWith("example.test|");
        }
    }

    @Test
    void malformedRequestsAreResetNotGuessedAt() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            int stream = 1;
            String[][] badExtras = {
                {"Upper-Case", "x"},                 // field names must be lower case
                {"connection", "keep-alive"},        // connection-specific fields are forbidden
                {"transfer-encoding", "chunked"},    // the classic downgrade-smuggling ingredient
                {"te", "gzip"},                      // only "trailers" is allowed
                {"bad name", "x"},                   // not a token
                {"x-a", "line\nbreak"},              // control characters in a value
                {"content-length", "5"},             // declared 5, sends 0 (END_STREAM on HEADERS)
                {"content-length", "1, 1"},
            };
            for (String[] extra : badExtras) {
                client.get(stream, "/hello", extra);
                Frame reset = client.next(RST_STREAM, stream);
                assertThat(reset.errorCode()).as(extra[0] + ": " + extra[1])
                    .isEqualTo(Http2Connection.PROTOCOL_ERROR);
                stream += 2;
            }

            // missing :path
            client.send(HEADERS, 0x4 | 0x1, stream, client.headerBlock("GET", null));
            assertThat(client.next(RST_STREAM, stream).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
            stream += 2;

            // a pseudo-header after a regular one
            Hpack.Encoder encoder = new Hpack.Encoder();
            encoder.field(":method", "GET");
            encoder.field(":scheme", "http");
            encoder.field("accept", "*/*");
            encoder.field(":path", "/hello");
            client.send(HEADERS, 0x4 | 0x1, stream, encoder.toByteArray());
            assertThat(client.next(RST_STREAM, stream).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
            stream += 2;

            // CONNECT and non-ASCII targets are refused as well
            client.send(HEADERS, 0x4 | 0x1, stream, client.headerBlock("CONNECT", "/hello"));
            assertThat(client.next(RST_STREAM, stream).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
            stream += 2;
            client.get(stream, "/café");
            assertThat(client.next(RST_STREAM, stream).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
            stream += 2;

            // the connection itself is fine and keeps serving
            client.get(stream, "/hello");
            assertThat(client.responseHeaders(client.next(HEADERS, stream))).containsEntry(":status", "200");
        }
    }

    @Test
    void bodyLongerThanItsContentLengthIsRejected() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            client.send(HEADERS, 0x4, 1, client.headerBlock("POST", "/echo", "content-length", "3"));
            client.send(DATA, 0x1, 1, "too long".getBytes(StandardCharsets.US_ASCII));
            assertThat(client.next(RST_STREAM, 1).errorCode()).isIn(
                Http2Connection.PROTOCOL_ERROR, Http2Connection.FLOW_CONTROL_ERROR);
        }
    }

    @Test
    void rapidResetIsStopped() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            Frame goAway = null;
            try {
                // open a stream and cancel it at once, over and over (CVE-2023-44487)
                for (int i = 0; i < 2000 && goAway == null; i++) {
                    int stream = 1 + 2 * i;
                    client.get(stream, "/hello");
                    client.send(RST_STREAM, 0, stream, intBytes(Http2Connection.CANCEL));
                }
            } catch (IOException serverHungUp) {
                // expected once the server has had enough
            }
            goAway = client.next(GOAWAY);
            assertThat(goAway.errorCode()).isEqualTo(Http2Connection.ENHANCE_YOUR_CALM);
            assertThat(client.closedByServer()).isTrue();
        }
        // the server as a whole is unaffected
        try (RawClient client = new RawClient()) {
            client.get(1, "/hello");
            assertThat(client.responseHeaders(client.next(HEADERS, 1))).containsEntry(":status", "200");
        }
    }

    @Test
    void endlessContinuationFramesAreStopped() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            // a header block that never ends (the 2024 "CONTINUATION flood")
            client.send(HEADERS, 0x1, 1, client.headerBlock("GET", "/hello"));
            byte[] filler = new byte[4096];
            try {
                for (int i = 0; i < 100; i++) {
                    client.send(CONTINUATION, 0, 1, filler);
                }
            } catch (IOException serverHungUp) {
                // expected
            }
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.ENHANCE_YOUR_CALM);
            assertThat(client.closedByServer()).isTrue();
        }
    }

    @Test
    void controlFrameFloodsAreStopped() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            try {
                for (int i = 0; i < 5000; i++) {
                    client.send(PING, 0, 0, new byte[8]);
                }
            } catch (IOException serverHungUp) {
                // expected
            }
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.ENHANCE_YOUR_CALM);
        }
        try (RawClient client = new RawClient()) {
            try {
                for (int i = 0; i < 5000; i++) {
                    client.send(SETTINGS, 0, 0, new byte[0]);
                }
            } catch (IOException serverHungUp) {
                // expected
            }
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.ENHANCE_YOUR_CALM);
        }
    }

    @Test
    void concurrentStreamsAreCappedEvenWhenTheClientResetsThem() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Ligero created = newApp(config().build());
        created.get("/slow", ctx -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                // a reset stream interrupts its handler; pretend the work takes a little longer anyway
                release.await(2, TimeUnit.SECONDS);
            }
            ctx.text("done");
        });
        startPlain(created, PlumaServerEngine.Limits.defaults().withMaxConcurrentStreams(2));
        try (RawClient client = new RawClient()) {
            client.get(1, "/slow");
            client.get(3, "/slow");
            client.get(5, "/slow");
            assertThat(client.next(RST_STREAM, 5).errorCode()).isEqualTo(Http2Connection.REFUSED_STREAM);

            // cancelling a stream does not free its slot until the handler has really returned
            client.send(RST_STREAM, 0, 1, intBytes(Http2Connection.CANCEL));
            client.get(7, "/slow");
            assertThat(client.next(RST_STREAM, 7).errorCode()).isEqualTo(Http2Connection.REFUSED_STREAM);

            release.countDown();
            assertThat(client.responseHeaders(client.next(HEADERS, 3))).containsEntry(":status", "200");
        }
    }

    @Test
    void responsesRespectTheClientsFlowControlWindow() throws Exception {
        Ligero created = newApp(config().build());
        created.get("/letters", ctx -> ctx.text("abcdefghijklmnopqrstuvwxyz"));
        startPlain(created, PlumaServerEngine.Limits.defaults());

        // SETTINGS_INITIAL_WINDOW_SIZE (0x4) = 10: at most 10 body bytes until we grant more
        byte[] settings = {0, 4, 0, 0, 0, 10};
        try (RawClient client = new RawClient(settings)) {
            client.get(1, "/letters");
            client.next(HEADERS, 1);
            Frame first = client.next(DATA, 1);
            assertThat(new String(first.payload(), StandardCharsets.US_ASCII)).isEqualTo("abcdefghij");
            assertThat(first.flags() & 0x1).as("not finished yet").isZero();

            // nothing more arrives while the window is closed
            client.socket.setSoTimeout(300);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.next(DATA, 1))
                .isInstanceOf(SocketTimeoutException.class);
            client.socket.setSoTimeout(5000);

            client.send(WINDOW_UPDATE, 0, 1, intBytes(100));
            StringBuilder rest = new StringBuilder();
            Frame frame;
            do {
                frame = client.next(DATA, 1);
                rest.append(new String(frame.payload(), StandardCharsets.US_ASCII));
            } while ((frame.flags() & 0x1) == 0);
            assertThat(rest).hasToString("klmnopqrstuvwxyz");
        }
    }

    @Test
    void protocolViolationsEndTheConnectionWithTheRightCode() throws Exception {
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());

        // a frame larger than the maximum frame size
        try (RawClient client = new RawClient()) {
            client.sendWithLength(DATA, 0, 1, new byte[0], 20_000);
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.FRAME_SIZE_ERROR);
        }
        // WINDOW_UPDATE of zero on the connection
        try (RawClient client = new RawClient()) {
            client.send(WINDOW_UPDATE, 0, 0, intBytes(0));
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
        }
        // a window that would overflow 2^31-1
        try (RawClient client = new RawClient()) {
            client.send(WINDOW_UPDATE, 0, 0, intBytes(0x7fffffff));
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.FLOW_CONTROL_ERROR);
        }
        // even stream ids are the server's; a client may not use them
        try (RawClient client = new RawClient()) {
            client.get(2, "/hello");
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
        }
        // DATA for a stream that was never opened
        try (RawClient client = new RawClient()) {
            client.send(DATA, 0, 9, new byte[1]);
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.PROTOCOL_ERROR);
        }
        // garbage where a header block should be
        try (RawClient client = new RawClient()) {
            client.send(HEADERS, 0x4 | 0x1, 1, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.COMPRESSION_ERROR);
        }
        // a header list larger than the advertised limit
        try (RawClient client = new RawClient()) {
            Hpack.Encoder encoder = new Hpack.Encoder();
            encoder.field(":method", "GET");
            encoder.field(":scheme", "http");
            encoder.field(":path", "/hello");
            for (int i = 0; i < 6; i++) {
                encoder.field("x-padding-" + i, "v".repeat(3000));
            }
            byte[] block = encoder.toByteArray();
            client.send(HEADERS, 0x1, 1, java.util.Arrays.copyOfRange(block, 0, 16_000));
            client.send(CONTINUATION, 0x4, 1, java.util.Arrays.copyOfRange(block, 16_000, block.length));
            assertThat(client.next(GOAWAY).errorCode()).isIn(
                Http2Connection.ENHANCE_YOUR_CALM, Http2Connection.COMPRESSION_ERROR);
        }
        // and after all that, a well-behaved client is served normally
        try (RawClient client = new RawClient()) {
            client.get(1, "/hello");
            assertThat(client.responseHeaders(client.next(HEADERS, 1))).containsEntry(":status", "200");
        }
    }

    @Test
    void shutdownAnnouncesGoAwayAndLetsRunningRequestsFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ligero created = newApp(config().build());
        created.get("/slow", ctx -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            ctx.text("finished");
        });
        startPlain(created, PlumaServerEngine.Limits.defaults());
        try (RawClient client = new RawClient()) {
            client.get(1, "/slow");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> stopping = CompletableFuture.runAsync(app::stop);
            Frame goAway = client.next(GOAWAY);
            assertThat(goAway.errorCode()).isEqualTo(Http2Connection.NO_ERROR);
            release.countDown();

            client.next(HEADERS, 1);
            assertThat(new String(client.next(DATA, 1).payload(), StandardCharsets.UTF_8)).isEqualTo("finished");
            stopping.get(15, TimeUnit.SECONDS);
            assertThat(client.closedByServer()).isTrue();
        }
        app = null;
    }

    @Test
    void idleHttp2ConnectionsAreClosed() throws Exception {
        startPlain(newApp(config().build()),
            PlumaServerEngine.Limits.defaults().withIdleTimeout(Duration.ofMillis(300)));
        try (RawClient client = new RawClient()) {
            client.get(1, "/hello");
            client.next(DATA, 1);
            assertThat(client.next(GOAWAY).errorCode()).isEqualTo(Http2Connection.NO_ERROR);
            assertThat(client.closedByServer()).isTrue();
        }
    }

    // ------------------------------------------------------------------ an independent client

    private static boolean curlSpeaksHttp2() {
        try {
            Process process = new ProcessBuilder("curl", "--version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 && output.contains("HTTP2");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Runs curl and returns its output followed by "\n<http version> <status>". */
    private static String curl(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("curl", "-sS", "--max-time", "20", "--noproxy", "*",
            "-w", "\n%{http_version} %{http_code}"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("HTTPS_PROXY");
        builder.environment().remove("https_proxy");
        builder.environment().remove("HTTP_PROXY");
        builder.environment().remove("http_proxy");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    /**
     * curl is built on nghttp2, an implementation we share no code with: if it
     * can talk to this server, the framing, HPACK and flow control are right
     * by more than our own reading of the RFCs.
     */
    @Test
    void interoperatesWithCurl() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(curlSpeaksHttp2(), "curl with HTTP/2 support not available");

        byte[] large = new byte[2_000_000];
        java.util.Arrays.fill(large, (byte) 'x');
        Ligero created = newApp(config().gzip(true).gzipMinBytes(100).build());
        created.get("/big", ctx -> ctx.text(new String(large, StandardCharsets.US_ASCII)));
        created.head("/hello", ctx -> ctx.text("hello over " + ctx.req().getProtocol()));
        String https = startTls(created, PlumaServerEngine.Limits.defaults());
        String cacert = certificates.certificatePem.toString();

        // TLS + ALPN
        assertThat(curl("--http2", "--cacert", cacert, https + "/hello")).isEqualTo("hello over HTTP/2.0\n2 200");
        assertThat(curl("--http2", "--cacert", cacert, "-d", "desde curl", https + "/echo"))
            .isEqualTo("desde curl\n2 200");
        assertThat(curl("--http2", "--cacert", cacert, "-I", https + "/hello"))
            .startsWith("HTTP/2 200").contains("content-length: 19");
        // a compressed 2 MB response: flow control and gzip together
        String big = curl("--http2", "--cacert", cacert, "--compressed", https + "/big");
        assertThat(big).hasSize(large.length + "\n2 200".length()).endsWith("\n2 200");
        // the same port still serves HTTP/1.1
        assertThat(curl("--http1.1", "--cacert", cacert, https + "/hello")).isEqualTo("hello over HTTP/1.1\n1.1 200");
        app.stop();

        // cleartext HTTP/2 with prior knowledge
        startPlain(newApp(config().build()), PlumaServerEngine.Limits.defaults());
        String http = "http://127.0.0.1:" + app.port();
        assertThat(curl("--http2-prior-knowledge", http + "/hello")).isEqualTo("hello over HTTP/2.0\n2 200");
        assertThat(curl("--http2-prior-knowledge", http + "/items/9")).isEqualTo("{\"id\":9}\n2 200");
        assertThat(curl("--http2-prior-knowledge", http + "/nope")).endsWith("\n2 404");
    }
}
