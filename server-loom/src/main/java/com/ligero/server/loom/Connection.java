package com.ligero.server.loom;

import com.ligero.http.BadRequestException;
import com.ligero.http.HttpHandler;
import com.ligero.http.PayloadTooLargeException;
import com.ligero.router.PathNormalizer;
import com.ligero.spi.EngineConfig;
import com.ligero.websocket.WsHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * One client connection: a loop that reads a request head from the socket,
 * serves it, and repeats while the connection is kept alive. Everything here
 * runs on the connection's own (virtual) thread with blocking I/O.
 */
final class Connection implements Runnable, BodySource {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    /** Unread request body we are willing to skip to keep a connection alive. */
    private static final int DRAIN_LIMIT = 256 * 1024;
    private static final int MAX_TRAILER_BYTES = 8 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final LoomServerEngine engine;
    /** The accepted TCP socket; {@link #socket} is the same one, or its TLS wrapper. */
    private final Socket rawSocket;
    private Socket socket;
    private final EngineConfig config;
    private final HttpHandler handler;
    private final LoomServerEngine.Limits limits;

    private InputStream in;
    private OutputStream out;
    private String remoteAddress;
    private byte[] buf = new byte[8 * 1024];
    private int pos;
    private int lim;
    private int currentTimeoutMillis = -1;
    private volatile boolean idle;

    /** Reusable scratch space for assembling responses. */
    byte[] outBuf = new byte[8 * 1024];

    // ---- body state of the request being served ----
    private boolean chunked;
    private long bodyRemaining;
    private boolean bodyDone;
    private boolean expectContinue;
    private boolean continueSent;
    private BodyStream openStream;

    private final boolean dedicated;
    private final String peerAddress;
    private final long writeTimeoutNanos;
    /** Non-zero while a write is in progress: when it must have finished. */
    private volatile long writeDeadlineNanos;

    Connection(LoomServerEngine engine, Socket socket, EngineConfig config, HttpHandler handler,
               LoomServerEngine.Limits limits, boolean dedicated, String peerAddress) {
        this.dedicated = dedicated;
        this.peerAddress = peerAddress;
        this.writeTimeoutNanos = limits.writeTimeout().toNanos();
        this.engine = engine;
        this.rawSocket = socket;
        this.socket = socket;
        this.config = config;
        this.handler = handler;
        this.limits = limits;
    }

    @Override
    public void run() {
        try {
            rawSocket.setTcpNoDelay(true);
            rawSocket.setKeepAlive(true);
            Tls tls = engine.tls();
            if (tls != null) {
                // The handshake runs here, on the connection's thread, and under the
                // same deadline as a request head: a client cannot stall it for long.
                rawSocket.setSoTimeout((int) Math.max(1, limits.headerTimeout().toMillis()));
                javax.net.ssl.SSLSocket secure = tls.wrap(rawSocket, "h2", "http/1.1");
                secure.startHandshake();
                socket = secure;
                currentTimeoutMillis = -1;
            }
            in = socket.getInputStream();
            out = socket.getOutputStream();
            if (socket instanceof javax.net.ssl.SSLSocket secure && "h2".equals(secure.getApplicationProtocol())) {
                // the client chose HTTP/2 during the TLS handshake (ALPN)
                serveHttp2(in, 0, true);
                return;
            }
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            remoteAddress = remote == null || remote.getAddress() == null
                ? null : remote.getAddress().getHostAddress();
            while (!engine.isStopping()) {
                RequestHead head;
                try {
                    head = readHead();
                } catch (ProtocolException e) {
                    writeError(e.status(), e.getMessage());
                    return;
                } catch (SocketTimeoutException e) {
                    if (lim > pos) {
                        writeError(408, "Request Timeout"); // a head was started but never finished
                    }
                    return;
                }
                if (head == HTTP2_PREFACE) {
                    // HTTP/2 without TLS, "prior knowledge": the rest of the preface and
                    // every frame after it come from our buffer first, then from the socket
                    serveHttp2(new BufferedThenSocket(), HTTP2_PREFACE_HEAD.length, false);
                    return;
                }
                if (head == null || !serve(head)) {
                    return;
                }
            }
        } catch (IOException e) {
            log.trace("Connection closed: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Connection failed", e);
        } finally {
            forceClose();
            engine.connectionClosed(this);
        }
    }

    /** What the first 18 bytes of an HTTP/2 connection look like to an HTTP/1 parser. */
    private static final byte[] HTTP2_PREFACE_HEAD = "PRI * HTTP/2.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final RequestHead HTTP2_PREFACE = new RequestHead("PRI", "*", false, new String[0],
        new String[0], 0, 0, false, false, false, false);

    private volatile Http2Connection http2;

    private void serveHttp2(InputStream input, int prefaceConsumed, boolean secure) throws IOException {
        Http2Connection connection = new Http2Connection(this, input, config, handler, limits,
            engine.streamExecutor(), engine.compression(), secure);
        http2 = connection;
        connection.run(prefaceConsumed);
    }

    /** Reads what {@link #readHead} already buffered, then straight from the socket. */
    private final class BufferedThenSocket extends InputStream {
        @Override
        public int read() throws IOException {
            if (pos < lim) {
                return buf[pos++] & 0xff;
            }
            return in.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (pos < lim) {
                int n = Math.min(length, lim - pos);
                System.arraycopy(buf, pos, target, offset, n);
                pos += n;
                return n;
            }
            return in.read(target, offset, length);
        }
    }

    /** Sets how long the next reads may block. */
    void readTimeout(long millis) throws IOException {
        timeout(millis);
    }

    /** Serves one request; returns whether the connection can take another. */
    private boolean serve(RequestHead head) throws IOException {
        chunked = head.chunked;
        bodyRemaining = chunked ? -1 : head.contentLength;
        bodyDone = !chunked && bodyRemaining == 0;
        expectContinue = head.expectContinue && !bodyDone;
        continueSent = false;
        openStream = null;

        if (head.webSocketUpgrade && !config.webSockets().isEmpty()) {
            WsHandler wsHandler = config.webSockets().get(PathNormalizer.normalize(head.path()));
            if (wsHandler != null) {
                new WebSocketConnection(this, socket, in, out, wsHandler, limits).run(head);
                return false;
            }
        }

        LoomHttpRequest request = new LoomHttpRequest(this, head, config.maxBodyBytes(),
            head.http10 ? "HTTP/1.0" : "HTTP/1.1");
        LoomHttpResponse response = new LoomHttpResponse(this, head, config, engine.compression());
        try {
            handler.handle(request, response);
        } catch (Exception e) {
            // The core pipeline maps exceptions; reaching this point means the
            // pipeline itself failed. Never leak details.
            log.error("Request pipeline failed", e);
            if (response.isCommitted()) {
                return false;
            }
            try {
                response.status(500).contentType("text/plain; charset=utf-8").send("Internal server error");
            } catch (RuntimeException secondary) {
                return false;
            }
        }
        try {
            response.end();
        } catch (RuntimeException e) {
            return false; // client went away mid-response
        }
        return response.keepAlive();
    }

    /** Whether this connection runs on a dedicated platform thread. */
    boolean dedicated() {
        return dedicated;
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    boolean stopping() {
        return engine.isStopping();
    }

    void closeIfIdle() {
        Http2Connection connection = http2;
        if (connection != null) {
            connection.shutdown(); // GOAWAY: no new requests on this connection
            if (connection.quiescent()) {
                forceClose();
            }
            return;
        }
        if (idle) {
            forceClose();
        }
    }

    void forceClose() {
        try {
            // the TCP socket itself: closing the TLS layer first could block on a peer that is not reading
            rawSocket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    void write(byte[] bytes, int offset, int length) {
        writeDeadlineNanos = System.nanoTime() + writeTimeoutNanos | 1; // never 0 while writing
        try {
            out.write(bytes, offset, length);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not send response", e);
        } finally {
            writeDeadlineNanos = 0;
        }
    }

    /** Called by the engine's watchdog: drops a client that stopped reading our response. */
    void closeIfWriteOverdue(long nowNanos) {
        long deadline = writeDeadlineNanos;
        if (deadline != 0 && nowNanos - deadline > 0) {
            forceClose();
        }
    }

    /** Address the connection was accepted from (the key of the per-address limit). */
    String peerAddress() {
        return peerAddress;
    }

    private void writeError(int status, String reason) {
        String body = "{\"status\":" + status + ",\"error\":\"" + reason + "\"}";
        String response = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + body.length() + "\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Connection: close\r\n\r\n" + body;
        try {
            out.write(response.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
            return; // client already gone
        }
        lingeringClose();
    }

    /**
     * Ends the connection without destroying what we just sent. Closing a
     * socket that still has unread input makes TCP send a reset, and a reset
     * can discard our final message (an error response, a GOAWAY) before the
     * peer reads it. So: finish our side, then swallow what the peer is still
     * sending — briefly and within a bound — and only then close.
     */
    void lingeringClose() {
        try {
            socket.shutdownOutput();
            rawSocket.setSoTimeout(250);
            byte[] discard = new byte[8 * 1024];
            long budget = 256 * 1024;
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (budget > 0 && System.nanoTime() < deadline) {
                int read = in.read(discard);
                if (read < 0) {
                    break;
                }
                budget -= read;
            }
        } catch (IOException ignored) {
            // timed out or already closed: either way we are done
        }
    }

    // ------------------------------------------------------------------
    // Request head
    // ------------------------------------------------------------------

    /**
     * Reads and parses the next request head. Returns {@code null} when the
     * client closed the connection between requests.
     */
    private RequestHead readHead() throws IOException {
        // bytes already buffered (a pipelined request) start the clock right away
        long deadlineNanos = lim > pos ? System.nanoTime() + limits.headerTimeout().toNanos() : 0;
        int scanFrom = pos;
        while (true) {
            // tolerate blank lines before a request line (RFC 9112 §2.2)
            while (scanFrom == pos && lim - pos >= 2 && buf[pos] == '\r' && buf[pos + 1] == '\n') {
                pos += 2;
                scanFrom = pos;
            }
            int end = indexOfHeadEnd(scanFrom);
            if (end >= 0) {
                if (end - pos == HTTP2_PREFACE_HEAD.length
                        && java.util.Arrays.equals(buf, pos, end, HTTP2_PREFACE_HEAD, 0, HTTP2_PREFACE_HEAD.length)) {
                    pos = end;
                    return HTTP2_PREFACE;
                }
                RequestHead head = HeadParser.parse(buf, pos, end, limits.maxHeaders());
                pos = end;
                return head;
            }
            scanFrom = Math.max(pos, lim - 3);
            if (lim - pos >= limits.maxHeaderBytes()) {
                throw new ProtocolException(431, "Request Header Fields Too Large");
            }
            if (pos == lim) {
                pos = 0;
                lim = 0;
                scanFrom = 0;
            } else if (lim == buf.length) {
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, lim - pos);
                    scanFrom -= pos;
                    lim -= pos;
                    pos = 0;
                } else {
                    buf = java.util.Arrays.copyOf(buf, Math.min(buf.length * 2, limits.maxHeaderBytes() + 4));
                }
            }
            boolean waitingForFirstByte = lim == pos;
            if (waitingForFirstByte) {
                idle = true;
                timeout(limits.idleTimeout().toMillis());
            } else {
                long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000;
                if (remaining <= 0) {
                    throw new SocketTimeoutException("request head not received in time");
                }
                timeout(remaining);
            }
            int read;
            try {
                read = in.read(buf, lim, buf.length - lim);
            } finally {
                idle = false;
            }
            if (read < 0) {
                return null;
            }
            if (waitingForFirstByte) {
                // the whole head must arrive within this window, however slowly it trickles in
                deadlineNanos = System.nanoTime() + limits.headerTimeout().toNanos();
            }
            lim += read;
        }
    }

    /** Index just past {@code CRLF CRLF}, or -1 when the head is not complete yet. */
    private int indexOfHeadEnd(int from) {
        byte[] b = buf;
        for (int i = from, last = lim - 3; i < last; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private void timeout(long millis) throws IOException {
        int value = (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
        if (value != currentTimeoutMillis) {
            socket.setSoTimeout(value);
            currentTimeoutMillis = value;
        }
    }

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    /**
     * Reads the whole request body, enforcing {@code maxBytes}.
     *
     * @throws PayloadTooLargeException when the body exceeds the limit
     * @throws BadRequestException      when chunked framing is malformed
     */
    @Override
    public byte[] readBody(long maxBytes) {
        if (bodyDone) {
            return EMPTY;
        }
        try {
            if (!chunked) {
                if (bodyRemaining > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                sendContinueIfExpected();
                timeout(limits.readTimeout().toMillis());
                int length = (int) bodyRemaining;
                byte[] body;
                if (length <= 64 * 1024) {
                    body = new byte[length];
                    readFully(body, 0, length);
                } else {
                    // Grow as bytes actually arrive: a declared length alone must
                    // not make the server allocate megabytes per connection.
                    ByteArrayOutputStream collected = new ByteArrayOutputStream(64 * 1024);
                    byte[] chunk = new byte[16 * 1024];
                    int remaining = length;
                    while (remaining > 0) {
                        int n = Math.min(chunk.length, remaining);
                        readFully(chunk, 0, n);
                        collected.write(chunk, 0, n);
                        remaining -= n;
                    }
                    body = collected.toByteArray();
                }
                bodyRemaining = 0;
                bodyDone = true;
                return body;
            }
            sendContinueIfExpected();
            timeout(limits.readTimeout().toMillis());
            ByteArrayOutputStream collected = new ByteArrayOutputStream(1024);
            byte[] chunk = new byte[8 * 1024];
            long total = 0;
            while (true) {
                int size = readChunkSize();
                if (size == 0) {
                    readTrailers();
                    bodyDone = true;
                    return collected.toByteArray();
                }
                total += size;
                if (total > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                int remaining = size;
                while (remaining > 0) {
                    int n = Math.min(chunk.length, remaining);
                    readFully(chunk, 0, n);
                    collected.write(chunk, 0, n);
                    remaining -= n;
                }
                expectCrLf();
            }
        } catch (ProtocolException e) {
            throw new BadRequestException("Malformed chunked request body");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read request body", e);
        }
    }

    /**
     * Brings the connection to the start of the next request by skipping
     * whatever is left of this request's body. Returns false when that is not
     * possible (too much left, or broken framing) and the connection must be
     * closed after the response.
     */
    boolean finishBody() {
        if (bodyDone) {
            return true;
        }
        if (expectContinue && !continueSent) {
            return false; // the client is still waiting for our go-ahead; nothing to skip
        }
        try {
            timeout(limits.readTimeout().toMillis());
            long budget = DRAIN_LIMIT;
            if (openStream != null) {
                // a handler abandoned its body stream part-way: finish it from where it stopped
                byte[] scratch = new byte[8 * 1024];
                for (int n = openStream.read(scratch); n >= 0; n = openStream.read(scratch)) {
                    budget -= n;
                    if (budget < 0) {
                        return false;
                    }
                }
                return bodyDone;
            }
            if (!chunked) {
                if (bodyRemaining > budget) {
                    return false;
                }
                skip(bodyRemaining);
            } else {
                while (true) {
                    int size = readChunkSize();
                    if (size == 0) {
                        readTrailers();
                        break;
                    }
                    budget -= size;
                    if (budget < 0) {
                        return false;
                    }
                    skip(size);
                    expectCrLf();
                }
            }
            bodyDone = true;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public InputStream openBodyStream(long maxBytes) {
        if (!chunked && bodyRemaining > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        openStream = new BodyStream(maxBytes);
        return openStream;
    }

    /**
     * The request body handed to the application piece by piece, straight
     * from the socket: memory use stays constant however large the upload.
     */
    private final class BodyStream extends InputStream {

        private final long maxBytes;
        private long delivered;
        private int chunkRemaining;
        private boolean chunkOpen;
        private boolean started;

        BodyStream(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (bodyDone) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            if (!started) {
                started = true;
                sendContinueIfExpected();
                timeout(limits.readTimeout().toMillis());
            }
            if (!chunked) {
                int n = readSome(target, offset, (int) Math.min(length, bodyRemaining));
                bodyRemaining -= n;
                bodyDone = bodyRemaining == 0;
                return n;
            }
            if (chunkRemaining == 0) {
                if (chunkOpen) {
                    expectCrLf();
                    chunkOpen = false;
                }
                int size = readChunkSize();
                if (size == 0) {
                    readTrailers();
                    bodyDone = true;
                    return -1;
                }
                delivered += size;
                if (delivered > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                chunkRemaining = size;
                chunkOpen = true;
            }
            int n = readSome(target, offset, Math.min(length, chunkRemaining));
            chunkRemaining -= n;
            return n;
        }
    }

    /** Up to {@code length} bytes: whatever is buffered, otherwise one read from the socket. */
    private int readSome(byte[] target, int offset, int length) throws IOException {
        int buffered = Math.min(length, lim - pos);
        if (buffered > 0) {
            System.arraycopy(buf, pos, target, offset, buffered);
            pos += buffered;
            return buffered;
        }
        int read = in.read(target, offset, length);
        if (read < 0) {
            throw new EOFException("connection closed mid-request");
        }
        return read;
    }

    private void sendContinueIfExpected() throws IOException {
        if (expectContinue && !continueSent) {
            continueSent = true;
            out.write(CONTINUE);
        }
    }

    private void fill() throws IOException {
        pos = 0;
        lim = 0;
        int read = in.read(buf, 0, buf.length);
        if (read < 0) {
            throw new EOFException("connection closed mid-request");
        }
        lim = read;
    }

    private int readByte() throws IOException {
        if (pos == lim) {
            fill();
        }
        return buf[pos++] & 0xff;
    }

    private void readFully(byte[] target, int offset, int length) throws IOException {
        int done = 0;
        int buffered = Math.min(length, lim - pos);
        if (buffered > 0) {
            System.arraycopy(buf, pos, target, offset, buffered);
            pos += buffered;
            done = buffered;
        }
        while (done < length) {
            int read = in.read(target, offset + done, length - done);
            if (read < 0) {
                throw new EOFException("connection closed mid-request");
            }
            done += read;
        }
    }

    private void skip(long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            if (pos == lim) {
                fill();
            }
            int n = (int) Math.min(remaining, lim - pos);
            pos += n;
            remaining -= n;
        }
    }

    /** {@code chunk-size [; extensions] CRLF} — hexadecimal, bounded. */
    private int readChunkSize() throws IOException {
        int size = 0;
        int digits = 0;
        int c = readByte();
        while (true) {
            int digit = Character.digit(c, 16);
            if (digit < 0) {
                break;
            }
            if (++digits > 7) {
                throw new ProtocolException(400, "Bad Request"); // > 256 MiB in one chunk
            }
            size = (size << 4) | digit;
            c = readByte();
        }
        if (digits == 0) {
            throw new ProtocolException(400, "Bad Request");
        }
        int extension = 0;
        while (c != '\r') {
            // chunk extensions are ignored, but neither unbounded nor allowed to hide a line break
            if (c == '\n' || ++extension > 256) {
                throw new ProtocolException(400, "Bad Request");
            }
            c = readByte();
        }
        if (readByte() != '\n') {
            throw new ProtocolException(400, "Bad Request");
        }
        return size;
    }

    private void expectCrLf() throws IOException {
        if (readByte() != '\r' || readByte() != '\n') {
            throw new ProtocolException(400, "Bad Request");
        }
    }

    /** Trailer fields are read and discarded, within a bound. */
    private void readTrailers() throws IOException {
        int total = 0;
        int lineLength = 0;
        while (true) {
            int c = readByte();
            if (++total > MAX_TRAILER_BYTES) {
                throw new ProtocolException(400, "Bad Request");
            }
            if (c == '\r') {
                if (readByte() != '\n') {
                    throw new ProtocolException(400, "Bad Request");
                }
                if (lineLength == 0) {
                    return;
                }
                lineLength = 0;
            } else if (c == '\n') {
                throw new ProtocolException(400, "Bad Request");
            } else {
                lineLength++;
            }
        }
    }
}
