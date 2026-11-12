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
final class Connection implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    /** Unread request body we are willing to skip to keep a connection alive. */
    private static final int DRAIN_LIMIT = 256 * 1024;
    private static final int MAX_TRAILER_BYTES = 8 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final LoomServerEngine engine;
    private final Socket socket;
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

    private final boolean dedicated;

    Connection(LoomServerEngine engine, Socket socket, EngineConfig config, HttpHandler handler,
               LoomServerEngine.Limits limits, boolean dedicated) {
        this.dedicated = dedicated;
        this.engine = engine;
        this.socket = socket;
        this.config = config;
        this.handler = handler;
        this.limits = limits;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            in = socket.getInputStream();
            out = socket.getOutputStream();
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

    /** Serves one request; returns whether the connection can take another. */
    private boolean serve(RequestHead head) throws IOException {
        chunked = head.chunked;
        bodyRemaining = chunked ? -1 : head.contentLength;
        bodyDone = !chunked && bodyRemaining == 0;
        expectContinue = head.expectContinue && !bodyDone;
        continueSent = false;

        if (head.webSocketUpgrade && !config.webSockets().isEmpty()) {
            WsHandler wsHandler = config.webSockets().get(PathNormalizer.normalize(head.path()));
            if (wsHandler != null) {
                new WebSocketConnection(this, socket, in, out, wsHandler, limits).run(head);
                return false;
            }
        }

        LoomHttpRequest request = new LoomHttpRequest(this, head, config.maxBodyBytes());
        LoomHttpResponse response = new LoomHttpResponse(this, head, config);
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

    String remoteAddress() {
        return remoteAddress;
    }

    boolean stopping() {
        return engine.isStopping();
    }

    void closeIfIdle() {
        if (idle) {
            forceClose();
        }
    }

    void forceClose() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    void write(byte[] bytes, int offset, int length) {
        try {
            out.write(bytes, offset, length);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not send response", e);
        }
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
            socket.shutdownOutput(); // lets the client read the answer before the close
        } catch (IOException ignored) {
            // client already gone
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
    byte[] readBody(long maxBytes) {
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
