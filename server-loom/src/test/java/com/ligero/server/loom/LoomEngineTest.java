package com.ligero.server.loom;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.http.NotFoundException;
import com.ligero.spi.ServerEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the Loom engine: ordinary HTTP through real clients,
 * and the protocol edges (framing, limits, timeouts) through raw sockets.
 */
class LoomEngineTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Ligero app;
    private LoomServerEngine engine;

    private static LigeroConfig.Builder config() {
        return LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0);
    }

    private String start(Ligero app) throws IOException {
        return start(app, LoomServerEngine.Limits.defaults());
    }

    private String start(Ligero app, LoomServerEngine.Limits limits) throws IOException {
        this.app = app;
        this.engine = new LoomServerEngine(limits);
        app.engine(engine);
        app.start();
        return "http://127.0.0.1:" + app.port();
    }

    private static Ligero newApp() {
        return Ligero.create(config().build());
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends raw bytes and returns everything the server answers until it closes the connection. */
    private String raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return readAll(socket.getInputStream());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                collected.write(chunk, 0, n);
            }
        } catch (SocketTimeoutException e) {
            collected.write("<<TIMEOUT>>".getBytes(StandardCharsets.US_ASCII));
        }
        return collected.toString(StandardCharsets.ISO_8859_1);
    }

    private static String statusLine(String response) {
        int end = response.indexOf("\r\n");
        return end < 0 ? response : response.substring(0, end);
    }

    // ------------------------------------------------------------------ ordinary HTTP

    @Test
    void servesRoutesBodiesAndErrors() throws Exception {
        record Item(String name) {
        }
        String base = start(newApp()
            .get("/items/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParamAsLong("id"), "q", String.valueOf(ctx.queryParam("q")))))
            .post("/items", ctx -> ctx.status(201).header("Location", "/items/9").json(ctx.body(Item.class)))
            .get("/missing", ctx -> {
                throw new NotFoundException("nope");
            })
            .get("/empty", ctx -> ctx.status(204).res().end())
            .get("/redirect", ctx -> ctx.redirect("/items/1", 307)));

        HttpResponse<String> found = get(base + "/items/42?q=a%20b");
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).contains("\"id\":42").contains("\"q\":\"a b\"");
        assertThat(found.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(found.headers().firstValue("Date")).isPresent();
        assertThat(found.headers().firstValue("X-Content-Type-Options")).contains("nosniff");

        HttpResponse<String> created = client.send(HttpRequest.newBuilder(URI.create(base + "/items"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ada\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).contains("/items/9");
        assertThat(created.body()).isEqualTo("{\"name\":\"Ada\"}");

        assertThat(get(base + "/missing").statusCode()).isEqualTo(404);
        assertThat(get(base + "/nowhere").statusCode()).isEqualTo(404);
        assertThat(get(base + "/items/abc").statusCode()).isEqualTo(400);

        HttpResponse<String> empty = get(base + "/empty");
        assertThat(empty.statusCode()).isEqualTo(204);
        assertThat(empty.headers().firstValue("Content-Length")).isEmpty();

        HttpResponse<String> redirect = get(base + "/redirect");
        assertThat(redirect.statusCode()).isEqualTo(307);
        assertThat(redirect.headers().firstValue("Location")).contains("/items/1");
    }

    @Test
    void keepsConnectionsAliveAndServesPipelinedRequestsInOrder() throws Exception {
        start(newApp().get("/n/{n}", ctx -> ctx.text("n=" + ctx.pathParam("n"))));

        String response = raw(
            "GET /n/1 HTTP/1.1\r\nHost: x\r\n\r\n"
            + "GET /n/2 HTTP/1.1\r\nHost: x\r\n\r\n"
            + "GET /n/3 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(response.split("HTTP/1.1 200 OK", -1)).hasSize(4);
        assertThat(response.indexOf("n=1")).isLessThan(response.indexOf("n=2"));
        assertThat(response.indexOf("n=2")).isLessThan(response.indexOf("n=3"));
        assertThat(response).containsOnlyOnce("Connection: close");
    }

    @Test
    void decodesChunkedRequestBodies() throws Exception {
        start(newApp().post("/echo", ctx -> ctx.text(ctx.bodyAsString())));

        String response = raw("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
            + "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-Trailer: ignored\r\n\r\n");

        assertThat(statusLine(response)).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response).endsWith("hello world");
    }

    @Test
    void headRequestsCarryHeadersButNoBody() throws Exception {
        start(newApp().head("/doc", ctx -> ctx.text("twelve bytes")));

        String response = raw("HEAD /doc HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(statusLine(response)).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response).contains("Content-Length: 12").endsWith("\r\n\r\n");
    }

    @Test
    void streamsServerSentEventsWithChunkedEncoding() throws Exception {
        String base = start(newApp().get("/events", ctx -> {
            try (var sse = ctx.sse()) {
                sse.send("tick", "1");
                sse.send("tick", "2");
            }
        }));

        HttpResponse<String> response = get(base + "/events");
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Transfer-Encoding")).contains("chunked");
        assertThat(response.body()).isEqualTo("event: tick\ndata: 1\n\nevent: tick\ndata: 2\n\n");
    }

    @Test
    void streamsLargeBodiesAndFinishesTheStreamIfTheHandlerForgets() throws Exception {
        byte[] payload = new byte[100_000];
        java.util.Arrays.fill(payload, (byte) 'x');
        String base = start(newApp().get("/big", ctx -> {
            OutputStream out = ctx.res().contentType("application/octet-stream").getOutputStream();
            out.write(payload); // never closed by the handler
        }));

        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + "/big")).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.body()).isEqualTo(payload);
        // the connection is still usable afterwards
        assertThat(get(base + "/big").statusCode()).isEqualTo(200);
    }

    @Test
    void http10ClientsAreServedAndTheConnectionIsClosed() throws Exception {
        start(newApp().get("/old", ctx -> ctx.text("still works")));

        String response = raw("GET /old HTTP/1.0\r\n\r\n");

        assertThat(statusLine(response)).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response).contains("Connection: close").endsWith("still works");
    }

    @Test
    void gzipsWhenEnabledAndAccepted() throws Exception {
        String text = "ligero ".repeat(500);
        String base = start(Ligero.create(config().gzip(true).gzipMinBytes(100).build())
            .get("/text", ctx -> ctx.text(text)));

        HttpResponse<byte[]> gzipped = client.send(HttpRequest.newBuilder(URI.create(base + "/text"))
            .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(gzipped.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(gzipped.headers().allValues("Vary")).contains("Accept-Encoding");
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(text);
        }
        assertThat(get(base + "/text").headers().firstValue("Content-Encoding")).isEmpty();
    }

    @Test
    void expect100ContinueIsHonoured() throws Exception {
        start(newApp().post("/upload", ctx -> ctx.text("got " + ctx.bodyAsBytes().length)));

        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] interim = socket.getInputStream().readNBytes(25);
            assertThat(new String(interim, StandardCharsets.US_ASCII)).isEqualTo("HTTP/1.1 100 Continue\r\n\r\n");
            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readAll(socket.getInputStream())).startsWith("HTTP/1.1 200 OK").endsWith("got 5");
        }
    }

    @Test
    void unreadRequestBodiesDoNotCorruptTheNextRequest() throws Exception {
        start(newApp()
            .post("/ignore", ctx -> ctx.text("ignored"))     // never reads its body
            .get("/next", ctx -> ctx.text("next")));

        String response = raw("POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\nGET /evil H"
            + "GET /next HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        // the 11 body bytes are skipped, not parsed as a request
        assertThat(response).contains("ignored").endsWith("next");
        assertThat(response.split("HTTP/1.1 200 OK", -1)).hasSize(3);
    }

    @Test
    void largeUnreadBodyClosesTheConnectionInsteadOfBeingDrained() throws Exception {
        start(newApp().post("/ignore", ctx -> ctx.text("ignored")));

        String response = raw("POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 5000000\r\n\r\npartial");

        assertThat(statusLine(response)).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response).contains("Connection: close").endsWith("ignored");
    }

    // ------------------------------------------------------------------ strictness

    @Test
    void ambiguousOrMalformedRequestsAreRefused() throws Exception {
        start(newApp().any("/x", ctx -> ctx.text("served")));
        String host = "Host: x\r\n";

        Map<String, String> cases = new java.util.LinkedHashMap<>();
        // request smuggling: two ways to delimit the body
        cases.put("POST /x HTTP/1.1\r\n" + host + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", "400");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Content-Length: 4\r\nContent-Length: 5\r\n\r\nabcde", "400");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Content-Length: +4\r\n\r\nabcd", "400");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Content-Length: 4, 4\r\n\r\nabcd", "400");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Content-Length: 0x4\r\n\r\nabcd", "400");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Transfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n", "501");
        cases.put("POST /x HTTP/1.1\r\n" + host + "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", "400");
        cases.put("POST /x HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", "400");
        // header syntax
        cases.put("GET /x HTTP/1.1\r\n" + host + "X-A: 1\r\n folded\r\n\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + "X-A : 1\r\n\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + "X-A: a\u0000b\r\n\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + "NoColonHere\r\n\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + ": empty-name\r\n\r\n", "400");
        // Host
        cases.put("GET /x HTTP/1.1\r\n\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + "Host: y\r\n\r\n", "400");
        // request line
        cases.put("GET /x HTTP/2.0\r\n" + host + "\r\n", "505");
        cases.put("GET /x\r\n" + host + "\r\n", "400");
        cases.put("GET  /x HTTP/1.1\r\n" + host + "\r\n", "400");
        cases.put("G<T /x HTTP/1.1\r\n" + host + "\r\n", "400");
        cases.put("GET x HTTP/1.1\r\n" + host + "\r\n", "400");
        cases.put("GET /café HTTP/1.1\r\n" + host + "\r\n", "400");
        cases.put("GET /x HTTP/1.1\r\n" + host + "Expect: something-else\r\n\r\n", "417");

        for (Map.Entry<String, String> testCase : cases.entrySet()) {
            String response = raw(testCase.getKey());
            assertThat(statusLine(response)).as(testCase.getKey()).startsWith("HTTP/1.1 " + testCase.getValue());
            assertThat(response).as(testCase.getKey()).contains("Connection: close").doesNotContain("served");
        }
        // and the server is perfectly healthy afterwards
        assertThat(raw("GET /x HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).endsWith("served");
    }

    @Test
    void malformedChunkedBodiesAreBadRequests() throws Exception {
        start(newApp().post("/echo", ctx -> ctx.text(ctx.bodyAsString())));
        String head = "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";

        for (String body : List.of("zz\r\nhello\r\n0\r\n\r\n", "5\r\nhelloXX0\r\n\r\n", "5\nhello\r\n0\r\n\r\n",
                "FFFFFFFFF\r\n", "\r\n")) {
            assertThat(statusLine(raw(head + body))).as(body).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void oversizedHeadsAndBodiesAreRejected() throws Exception {
        start(Ligero.create(config().maxBodyBytes(16).build()).post("/echo", ctx -> ctx.text(ctx.bodyAsString())));

        String hugeHeader = "GET /echo HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(20_000) + "\r\n\r\n";
        assertThat(statusLine(raw(hugeHeader))).startsWith("HTTP/1.1 431");

        StringBuilder manyHeaders = new StringBuilder("GET /echo HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 150; i++) {
            manyHeaders.append("X-").append(i).append(": v\r\n");
        }
        assertThat(statusLine(raw(manyHeaders + "\r\n"))).startsWith("HTTP/1.1 431");

        String declared = "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 64\r\n\r\n" + "x".repeat(64);
        assertThat(statusLine(raw(declared))).startsWith("HTTP/1.1 413");

        String chunked = "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "a\r\n0123456789\r\na\r\n0123456789\r\n0\r\n\r\n";
        assertThat(statusLine(raw(chunked))).startsWith("HTTP/1.1 413");

        String fits = "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello";
        assertThat(raw(fits)).endsWith("hello");
    }

    @Test
    void applicationDataCannotSplitTheResponse() throws Exception {
        String base = start(newApp().get("/set", ctx ->
            ctx.header("X-Echo", ctx.queryParam("v")).text("ok")));

        HttpResponse<String> attack = get(base + "/set?v=a%0d%0aSet-Cookie:%20admin=1");
        assertThat(attack.statusCode()).isEqualTo(500);
        assertThat(attack.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(get(base + "/set?v=fine").headers().firstValue("X-Echo")).contains("fine");
    }

    @Test
    void applicationCannotOverrideFraming() throws Exception {
        start(newApp().get("/f", ctx -> ctx.header("Content-Length", "999").header("Transfer-Encoding", "chunked")
            .text("four")));

        String response = raw("GET /f HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        assertThat(response).contains("Content-Length: 4\r\n").doesNotContain("999").doesNotContain("chunked");
    }

    // ------------------------------------------------------------------ timeouts and limits

    @Test
    void slowRequestHeadsAreCutOff() throws Exception {
        LoomServerEngine.Limits limits = new LoomServerEngine.Limits(16 * 1024, 100, Duration.ofMillis(300),
            Duration.ofSeconds(30), Duration.ofSeconds(5), 100, 1024 * 1024, 4);
        start(newApp().get("/x", ctx -> ctx.text("ok")), limits);

        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            long begin = System.nanoTime();
            // one byte every 100 ms would keep a naive per-read timeout alive forever
            String head = "GET /x HTTP/1.1\r\nHost: x\r\n";
            int sent = 0;
            String response = null;
            try {
                for (; sent < head.length(); sent++) {
                    out.write(head.charAt(sent));
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException closedByServer) {
                // expected once the server gives up
            }
            response = readAll(socket.getInputStream());
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertThat(statusLine(response)).startsWith("HTTP/1.1 408");
            assertThat(elapsedMillis).isLessThan(3000);
        }
    }

    @Test
    void idleKeepAliveConnectionsAreClosed() throws Exception {
        LoomServerEngine.Limits limits = new LoomServerEngine.Limits(16 * 1024, 100, Duration.ofSeconds(5),
            Duration.ofMillis(300), Duration.ofSeconds(5), 100, 1024 * 1024, 4);
        start(newApp().get("/x", ctx -> ctx.text("ok")), limits);

        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = readAll(socket.getInputStream()); // returns when the server closes the idle connection
            assertThat(response).endsWith("ok").doesNotContain("<<TIMEOUT>>");
        }
    }

    @Test
    void connectionsBeyondTheLimitAreRefused() throws Exception {
        LoomServerEngine.Limits limits = new LoomServerEngine.Limits(16 * 1024, 100, Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofSeconds(5), 2, 1024 * 1024, 4);
        start(newApp().get("/x", ctx -> ctx.text("ok")), limits);

        try (Socket first = new Socket("127.0.0.1", app.port());
             Socket second = new Socket("127.0.0.1", app.port())) {
            first.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            second.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            first.setSoTimeout(3000);
            second.setSoTimeout(3000);
            assertThat((char) first.getInputStream().read()).isEqualTo('H');
            assertThat((char) second.getInputStream().read()).isEqualTo('H');

            try (Socket third = new Socket("127.0.0.1", app.port())) {
                third.setSoTimeout(3000);
                third.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                int answer;
                try {
                    answer = third.getInputStream().read();
                } catch (IOException reset) {
                    answer = -1;
                }
                assertThat(answer).as("the third connection is closed without being served").isEqualTo(-1);
            }
        }
    }

    @Test
    void servesMoreConnectionsThanDedicatedThreadsOnVirtualThreads() throws Exception {
        LoomServerEngine.Limits limits = LoomServerEngine.Limits.defaults().withDedicatedThreads(2);
        String base = start(newApp().get("/who", ctx -> ctx.text(Thread.currentThread().isVirtual() ? "virtual" : "platform")), limits);

        // hold 20 connections open at once; only two can be on dedicated threads
        List<Socket> sockets = new java.util.ArrayList<>();
        int virtual = 0;
        int platform = 0;
        try {
            for (int i = 0; i < 20; i++) {
                Socket socket = new Socket("127.0.0.1", app.port());
                socket.setSoTimeout(5000);
                sockets.add(socket);
                socket.getOutputStream().write("GET /who HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                byte[] bytes = new byte[512];
                int n = socket.getInputStream().read(bytes);
                String response = new String(bytes, 0, n, StandardCharsets.US_ASCII);
                if (response.endsWith("virtual")) {
                    virtual++;
                } else if (response.endsWith("platform")) {
                    platform++;
                }
            }
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
        assertThat(platform).isEqualTo(2);
        assertThat(virtual).isEqualTo(18);
        assertThat(get(base + "/who").statusCode()).isEqualTo(200);
    }

    @Test
    void virtualThreadsOnlyAndPlatformThreadsOnlyBothWork() throws Exception {
        String base = start(newApp().get("/who", ctx -> ctx.text(String.valueOf(Thread.currentThread().isVirtual()))),
            LoomServerEngine.Limits.defaults().withDedicatedThreads(0));
        assertThat(get(base + "/who").body()).isEqualTo("true");
        app.stop();

        base = start(Ligero.create(config().virtualThreads(false).build())
            .get("/who", ctx -> ctx.text(String.valueOf(Thread.currentThread().isVirtual()))));
        assertThat(get(base + "/who").body()).isEqualTo("false");
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void stopLetsInFlightRequestsFinishAndClosesIdleConnections() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String base = start(newApp().get("/slow", ctx -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            ctx.text("finished");
        }).get("/fast", ctx -> ctx.text("fast")));

        Socket idle = new Socket("127.0.0.1", app.port());
        idle.setSoTimeout(5000);
        idle.getOutputStream().write("GET /fast HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat((char) idle.getInputStream().read()).isEqualTo('H');

        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
            HttpRequest.newBuilder(URI.create(base + "/slow")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(app::stop);
        Thread.sleep(200);
        release.countDown();

        assertThat(inFlight.get(5, TimeUnit.SECONDS).body()).isEqualTo("finished");
        stopping.get(15, TimeUnit.SECONDS);
        assertThat(readAll(idle.getInputStream())).doesNotContain("<<TIMEOUT>>"); // closed by the server
        idle.close();
        assertThat(engine.openConnections()).isZero();
        app = null;
    }

    @Test
    void engineIsDiscoverableAndRestartable() throws Exception {
        assertThat(ServiceLoader.load(ServerEngine.class).stream()
            .map(provider -> provider.type().getName()))
            .contains(LoomServerEngine.class.getName());

        String base = start(newApp().get("/x", ctx -> ctx.text("one")));
        assertThat(get(base + "/x").body()).isEqualTo("one");
        int port = app.port();
        app.stop();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new Socket("127.0.0.1", port)))
            .isInstanceOf(IOException.class);

        base = start(newApp().get("/x", ctx -> ctx.text("two")));
        assertThat(get(base + "/x").body()).isEqualTo("two");
    }

    @Test
    void handlesConcurrentLoad() throws Exception {
        String base = start(newApp().get("/n/{n}", ctx -> ctx.text(ctx.pathParam("n"))));

        List<CompletableFuture<HttpResponse<String>>> pending = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            pending.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/n/" + i)).build(),
                HttpResponse.BodyHandlers.ofString()));
        }
        for (int i = 0; i < pending.size(); i++) {
            assertThat(pending.get(i).get(20, TimeUnit.SECONDS).body()).isEqualTo(String.valueOf(i));
        }
    }
}
