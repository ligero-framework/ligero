package com.ligero.server.loom;

import com.ligero.websocket.WsHandler;
import com.ligero.websocket.WsSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A WebSocket (RFC 6455) served on the connection's own thread: validates the
 * opening handshake — including the handler's origin check — and then reads
 * frames until either side closes.
 *
 * <p>Text messages are delivered to the {@link WsHandler}; fragmented
 * messages are reassembled up to a size limit; pings are answered; binary
 * messages are refused with close code 1003 because the handler API is
 * text-only.</p>
 */
final class WebSocketConnection implements WsSession {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Connection connection;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final WsHandler handler;
    private final int maxMessageBytes;
    private final String id = UUID.randomUUID().toString();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private String path;
    private volatile boolean closeSent;

    WebSocketConnection(Connection connection, Socket socket, InputStream in, OutputStream out,
                        WsHandler handler, LoomServerEngine.Limits limits) {
        this.connection = connection;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.handler = handler;
        this.maxMessageBytes = limits.maxWsMessageBytes();
    }

    /** Performs the handshake and, when it succeeds, serves the socket until it closes. */
    void run(RequestHead head) throws IOException {
        this.path = com.ligero.router.PathNormalizer.normalize(head.path());
        String key = head.header("Sec-WebSocket-Key");
        if (!"GET".equals(head.method) || head.http10 || key == null || !isValidKey(key)) {
            refuse(400, "Bad Request", null);
            return;
        }
        if (!"13".equals(head.header("Sec-WebSocket-Version"))) {
            refuse(426, "Upgrade Required", "Sec-WebSocket-Version: 13\r\n");
            return;
        }
        if (!handler.acceptOrigin(head.header("Origin"), head.header("Host"))) {
            refuse(403, "Forbidden", null); // cross-site handshake: no session is ever created
            return;
        }
        String accept = Base64.getEncoder().encodeToString(sha1(key + GUID));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.setSoTimeout(0); // an open socket may legitimately stay silent; TCP keep-alive detects dead peers

        int closeCode = 1006;
        String closeReason = "";
        try {
            handler.onConnect(this);
        } catch (Exception e) {
            handler.onError(this, e);
            close(1011, "connect handler failed");
            notifyClose(1011, "connect handler failed");
            return;
        }
        try {
            ByteArrayOutputStream message = null;
            while (true) {
                int b0 = readByte();
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                if ((b0 & 0x70) != 0 || (b1 & 0x80) == 0) {
                    // reserved bits (no extension was negotiated) or an unmasked client frame
                    close(1002, "protocol error");
                    closeCode = 1002;
                    break;
                }
                long payloadLength = b1 & 0x7f;
                if (payloadLength == 126) {
                    payloadLength = (readByte() << 8) | readByte();
                } else if (payloadLength == 127) {
                    payloadLength = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLength = (payloadLength << 8) | readByte();
                    }
                }
                boolean control = opcode >= 0x8;
                if (control && (!fin || payloadLength > 125)) {
                    close(1002, "protocol error");
                    closeCode = 1002;
                    break;
                }
                long buffered = message == null ? 0 : message.size();
                if (payloadLength < 0 || payloadLength + buffered > maxMessageBytes) {
                    close(1009, "message too big");
                    closeCode = 1009;
                    break;
                }
                byte[] mask = readExactly(4);
                byte[] payload = readExactly((int) payloadLength);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }

                if (opcode == OP_CLOSE) {
                    closeCode = payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : 1005;
                    closeReason = payload.length > 2
                        ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                    close(closeCode == 1005 ? 1000 : closeCode, ""); // complete the closing handshake
                    break;
                } else if (opcode == OP_PING) {
                    writeFrame(OP_PONG, payload);
                } else if (opcode == OP_PONG) {
                    // unsolicited pongs are allowed and ignored
                } else if (opcode == OP_TEXT || opcode == OP_CONTINUATION) {
                    if ((opcode == OP_TEXT) == (message != null)) {
                        // a new message while one is in flight, or a continuation of nothing
                        close(1002, "protocol error");
                        closeCode = 1002;
                        break;
                    }
                    if (fin && message == null) {
                        if (!deliver(payload)) {
                            closeCode = 1007;
                            break;
                        }
                    } else {
                        if (message == null) {
                            message = new ByteArrayOutputStream(Math.max(256, payload.length * 2));
                        }
                        message.write(payload, 0, payload.length);
                        if (fin) {
                            byte[] whole = message.toByteArray();
                            message = null;
                            if (!deliver(whole)) {
                                closeCode = 1007;
                                break;
                            }
                        }
                    }
                } else if (opcode == OP_BINARY) {
                    close(1003, "binary messages are not supported");
                    closeCode = 1003;
                    break;
                } else {
                    close(1002, "protocol error");
                    closeCode = 1002;
                    break;
                }
            }
        } catch (IOException e) {
            if (!closeSent && !connection.stopping()) {
                handler.onError(this, e);
            }
        } finally {
            notifyClose(closeCode, closeReason);
        }
    }

    /** Decodes strictly: invalid UTF-8 closes the socket with 1007 instead of being papered over. */
    private boolean deliver(byte[] payload) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            close(1007, "invalid UTF-8");
            return false;
        }
        try {
            handler.onMessage(this, text);
        } catch (Exception e) {
            handler.onError(this, e);
        }
        return true;
    }

    private void notifyClose(int code, String reason) {
        try {
            handler.onClose(this, code, reason);
        } catch (Exception e) {
            log.warn("WebSocket close handler failed for {}", path, e);
        }
    }

    private void refuse(int status, String reason, String extraHeaders) throws IOException {
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n"
            + (extraHeaders == null ? "" : extraHeaders) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.shutdownOutput();
    }

    /** A valid key is the base64 encoding of exactly 16 bytes. */
    private static boolean isValidKey(String key) {
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("WebSocket peer closed the connection");
        }
        return b;
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] bytes = new byte[length];
        int done = 0;
        while (done < length) {
            int read = in.read(bytes, done, length - done);
            if (read < 0) {
                throw new EOFException("WebSocket peer closed the connection");
            }
            done += read;
        }
        return bytes;
    }

    /** Server frames are never masked. Writers are serialized so frames cannot interleave. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int length = payload.length;
        byte[] frame;
        int offset;
        if (length <= 125) {
            frame = new byte[2 + length];
            frame[1] = (byte) length;
            offset = 2;
        } else if (length <= 0xffff) {
            frame = new byte[4 + length];
            frame[1] = 126;
            frame[2] = (byte) (length >>> 8);
            frame[3] = (byte) length;
            offset = 4;
        } else {
            frame = new byte[10 + length];
            frame[1] = 127;
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((long) length >>> (8 * (7 - i)));
            }
            offset = 10;
        }
        frame[0] = (byte) (0x80 | opcode);
        System.arraycopy(payload, 0, frame, offset, length);
        writeLock.lock();
        try {
            out.write(frame);
        } finally {
            writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------ WsSession

    @Override
    public String id() {
        return id;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public void send(String message) {
        if (closeSent) {
            return;
        }
        try {
            writeFrame(OP_TEXT, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            handler.onError(this, e);
        }
    }

    @Override
    public void close(int statusCode, String reason) {
        if (closeSent) {
            return;
        }
        closeSent = true;
        byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        int reasonLength = Math.min(text.length, 123); // control frames carry at most 125 bytes
        byte[] payload = new byte[2 + reasonLength];
        payload[0] = (byte) (statusCode >>> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(text, 0, payload, 2, reasonLength);
        try {
            writeFrame(OP_CLOSE, payload);
            socket.setSoTimeout(5_000); // do not wait forever for the peer's closing frame
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // the peer is already gone
        }
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }
}
