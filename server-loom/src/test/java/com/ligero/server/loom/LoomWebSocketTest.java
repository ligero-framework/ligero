package com.ligero.server.loom;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.websocket.WsHandler;
import com.ligero.websocket.WsSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** WebSockets on the Loom engine: a real client for the happy path, raw frames for the edges. */
class LoomWebSocketTest {

    private Ligero app;
    private final List<String> events = new CopyOnWriteArrayList<>();

    private final WsHandler echo = new WsHandler() {
        @Override
        public void onConnect(WsSession session) {
            events.add("connect " + session.path());
        }

        @Override
        public void onMessage(WsSession session, String message) {
            events.add("message " + message.length());
            if ("bye".equals(message)) {
                session.close(1000, "done");
            } else {
                session.send("echo: " + message);
            }
        }

        @Override
        public void onClose(WsSession session, int statusCode, String reason) {
            events.add("close " + statusCode);
        }

        @Override
        public void onError(WsSession session, Throwable error) {
            events.add("error " + error.getClass().getSimpleName());
        }
    };

    private void start() throws IOException {
        start(LoomServerEngine.Limits.defaults());
    }

    private void start(LoomServerEngine.Limits limits) throws IOException {
        app = Ligero.create(LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0).build());
        app.engine(new LoomServerEngine(limits));
        app.get("/plain", ctx -> ctx.text("http ok"));
        app.websocket("/echo", echo);
        app.start();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private void awaitEvent(String prefix) throws InterruptedException {
        for (int i = 0; i < 100 && events.stream().noneMatch(e -> e.startsWith(prefix)); i++) {
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------------ a real client

    @Test
    void echoConversationWithTheJdkClient() throws Exception {
        start();
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> two = new CompletableFuture<>();
        StringBuilder partial = new StringBuilder();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/echo"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        received.add(partial.toString());
                        partial.setLength(0);
                        if (received.size() == 2) {
                            two.complete(null);
                        }
                    }
                    webSocket.request(1);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);

        String large = "x".repeat(70_000); // needs the 64-bit length form on the way back
        socket.sendText("hola", true).get(5, TimeUnit.SECONDS);
        socket.sendText(large, true).get(5, TimeUnit.SECONDS);
        two.get(5, TimeUnit.SECONDS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

        assertThat(received).containsExactly("echo: hola", "echo: " + large);
        awaitEvent("close");
        assertThat(events).contains("connect /echo", "message 4", "message 70000", "close 1000");
        assertThat(events).noneMatch(e -> e.startsWith("error"));
    }

    @Test
    void httpRoutesWorkAlongsideWebSockets() throws Exception {
        start();
        var response = HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/plain")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("http ok");
    }

    // ------------------------------------------------------------------ raw frames

    private Socket handshake(String extraHeaders) throws IOException {
        Socket socket = new Socket("127.0.0.1", app.port());
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(("GET /echo HTTP/1.1\r\nHost: 127.0.0.1:" + app.port() + "\r\n"
            + "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
            + extraHeaders + "\r\n").getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /** A client frame; masked unless {@code mask} is false. */
    private static byte[] frame(int firstByte, byte[] payload, boolean mask) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(firstByte);
        int maskBit = mask ? 0x80 : 0;
        if (payload.length <= 125) {
            out.write(maskBit | payload.length);
        } else {
            out.write(maskBit | 126);
            out.write(payload.length >>> 8);
            out.write(payload.length);
        }
        if (mask) {
            byte[] key = {1, 2, 3, 4};
            out.writeBytes(key);
            for (int i = 0; i < payload.length; i++) {
                out.write(payload[i] ^ key[i & 3]);
            }
        } else {
            out.writeBytes(payload);
        }
        return out.toByteArray();
    }

    private static byte[] text(String message) {
        return frame(0x81, message.getBytes(StandardCharsets.UTF_8), true);
    }

    private record Frame(int opcode, byte[] payload) {
        int closeCode() {
            return ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        }

        String textPayload() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int b0 = data.readUnsignedByte();
        int b1 = data.readUnsignedByte();
        assertThat(b1 & 0x80).as("server frames are never masked").isZero();
        int length = b1 & 0x7f;
        if (length == 126) {
            length = data.readUnsignedShort();
        } else if (length == 127) {
            length = (int) data.readLong();
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return new Frame(b0 & 0x0f, payload);
    }

    @Test
    void handshakeAnswersWithTheRfcAcceptKey() throws Exception {
        start();
        try (Socket socket = handshake("")) {
            String head = readHead(socket.getInputStream());
            assertThat(head).startsWith("HTTP/1.1 101 Switching Protocols")
                .contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="); // the RFC 6455 example

            socket.getOutputStream().write(text("hi"));
            Frame reply = readFrame(socket.getInputStream());
            assertThat(reply.opcode()).isEqualTo(1);
            assertThat(reply.textPayload()).isEqualTo("echo: hi");
        }
    }

    @Test
    void crossSiteHandshakesAreRefusedBeforeAnySessionExists() throws Exception {
        start();
        try (Socket socket = handshake("Origin: https://evil.example\r\n")) {
            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 403");
        }
        try (Socket socket = handshake("Origin: http://127.0.0.1:" + app.port() + "\r\n")) {
            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 101");
        }
        Thread.sleep(100);
        assertThat(events.stream().filter(e -> e.startsWith("connect")).count()).isEqualTo(1);
    }

    @Test
    void badHandshakesAreRefused() throws Exception {
        start();
        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /echo HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: short\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 400");
        }
        try (Socket socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /echo HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 8\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 426").contains("Sec-WebSocket-Version: 13");
        }
        assertThat(events).isEmpty();
    }

    @Test
    void pingIsAnsweredAndFragmentsAreReassembled() throws Exception {
        start();
        try (Socket socket = handshake("")) {
            readHead(socket.getInputStream());
            socket.getOutputStream().write(frame(0x89, "are you there".getBytes(StandardCharsets.UTF_8), true));
            Frame pong = readFrame(socket.getInputStream());
            assertThat(pong.opcode()).isEqualTo(0xA);
            assertThat(pong.textPayload()).isEqualTo("are you there");

            // "frag" + (ping in the middle) + "mented": one message in two frames
            socket.getOutputStream().write(frame(0x01, "frag".getBytes(StandardCharsets.UTF_8), true));
            socket.getOutputStream().write(frame(0x89, new byte[0], true));
            socket.getOutputStream().write(frame(0x80, "mented".getBytes(StandardCharsets.UTF_8), true));
            assertThat(readFrame(socket.getInputStream()).opcode()).isEqualTo(0xA);
            assertThat(readFrame(socket.getInputStream()).textPayload()).isEqualTo("echo: fragmented");
        }
    }

    @Test
    void protocolViolationsCloseTheSocketWithTheRightCode() throws Exception {
        start(new LoomServerEngine.Limits(16 * 1024, 100, Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofSeconds(5), 100, 1000, 4));

        // unmasked client frame
        assertThat(closeCodeAfter(frame(0x81, "hi".getBytes(StandardCharsets.UTF_8), false))).isEqualTo(1002);
        // reserved bit set without a negotiated extension
        assertThat(closeCodeAfter(frame(0xC1, "hi".getBytes(StandardCharsets.UTF_8), true))).isEqualTo(1002);
        // continuation frame with nothing to continue
        assertThat(closeCodeAfter(frame(0x80, "hi".getBytes(StandardCharsets.UTF_8), true))).isEqualTo(1002);
        // fragmented control frame
        assertThat(closeCodeAfter(frame(0x09, new byte[0], true))).isEqualTo(1002);
        // unknown opcode
        assertThat(closeCodeAfter(frame(0x83, new byte[0], true))).isEqualTo(1002);
        // binary: the handler API is text-only
        assertThat(closeCodeAfter(frame(0x82, new byte[] {1, 2, 3}, true))).isEqualTo(1003);
        // invalid UTF-8 in a text frame
        assertThat(closeCodeAfter(frame(0x81, new byte[] {(byte) 0xC3, (byte) 0x28}, true))).isEqualTo(1007);
        // larger than the configured message limit (1000 bytes)
        assertThat(closeCodeAfter(frame(0x81, new byte[2000], true))).isEqualTo(1009);
    }

    private int closeCodeAfter(byte[] clientFrame) throws IOException {
        try (Socket socket = handshake("")) {
            readHead(socket.getInputStream());
            socket.getOutputStream().write(clientFrame);
            Frame close = readFrame(socket.getInputStream());
            assertThat(close.opcode()).isEqualTo(8);
            return close.closeCode();
        }
    }

    @Test
    void serverInitiatedCloseCompletesTheHandshake() throws Exception {
        start();
        try (Socket socket = handshake("")) {
            readHead(socket.getInputStream());
            socket.getOutputStream().write(text("bye"));
            Frame close = readFrame(socket.getInputStream());
            assertThat(close.opcode()).isEqualTo(8);
            assertThat(close.closeCode()).isEqualTo(1000);
            // answer the close; the server then drops the connection
            socket.getOutputStream().write(frame(0x88, new byte[] {0x03, (byte) 0xE8}, true));
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        }
        awaitEvent("close");
        assertThat(events).contains("close 1000");
    }

    @Test
    void abruptDisconnectIsReportedToTheHandler() throws Exception {
        start();
        Socket socket = handshake("");
        readHead(socket.getInputStream());
        socket.close();
        awaitEvent("close");
        assertThat(events).contains("close 1006").anyMatch(e -> e.startsWith("error"));
    }
}
