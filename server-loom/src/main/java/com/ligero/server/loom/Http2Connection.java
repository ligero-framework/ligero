package com.ligero.server.loom;

import com.ligero.http.HttpHandler;
import com.ligero.spi.EngineConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One HTTP/2 connection (RFC 9113). The connection's thread reads frames;
 * each request runs on its own thread and writes its response frames under a
 * shared lock.
 *
 * <p>HTTP/2 hands the peer many ways to make a server work for nothing, so
 * every one of them is bounded here:</p>
 * <ul>
 *   <li>concurrent streams are capped, and a stream the client resets keeps
 *       counting until its handler has really finished — which, together with
 *       a limit on how fast streams may be reset, defuses "rapid reset";</li>
 *   <li>header blocks are capped while they are being received, so a stream
 *       of CONTINUATION frames cannot grow without end;</li>
 *   <li>frames that cost us work but carry nothing (PING, SETTINGS, empty
 *       DATA, PRIORITY, zero-length CONTINUATION) are rate limited;</li>
 *   <li>request bodies are flow controlled: we only grant the peer more
 *       window as the application actually consumes data;</li>
 *   <li>responses respect the peer's windows, with a deadline, so a client
 *       that never opens its window cannot park our threads.</li>
 * </ul>
 */
final class Http2Connection {

    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int DATA = 0x0;
    private static final int HEADERS = 0x1;
    private static final int PRIORITY = 0x2;
    private static final int RST_STREAM = 0x3;
    private static final int SETTINGS = 0x4;
    private static final int PUSH_PROMISE = 0x5;
    private static final int PING = 0x6;
    private static final int GOAWAY = 0x7;
    private static final int WINDOW_UPDATE = 0x8;
    private static final int CONTINUATION = 0x9;

    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_PADDED = 0x8;
    private static final int FLAG_PRIORITY = 0x20;

    static final int NO_ERROR = 0x0;
    static final int PROTOCOL_ERROR = 0x1;
    static final int INTERNAL_ERROR = 0x2;
    static final int FLOW_CONTROL_ERROR = 0x3;
    static final int STREAM_CLOSED = 0x5;
    static final int FRAME_SIZE_ERROR = 0x6;
    static final int REFUSED_STREAM = 0x7;
    static final int CANCEL = 0x8;
    static final int COMPRESSION_ERROR = 0x9;
    static final int ENHANCE_YOUR_CALM = 0xb;

    /** The largest frame we accept and send (the protocol's default and minimum). */
    private static final int MAX_FRAME_SIZE = 16_384;
    private static final int DEFAULT_WINDOW = 65_535;
    private static final int HEADER_TABLE_SIZE = 4_096;

    /** Abuse thresholds, per 10-second window. */
    private static final long ABUSE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_RESETS_PER_WINDOW = 200;
    private static final int MAX_CONTROL_FRAMES_PER_WINDOW = 2_000;

    /** A connection-level protocol violation: answered with GOAWAY. */
    private static final class ConnectionError extends IOException {
        final int code;

        ConnectionError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Connection connection;
    private final InputStream in;
    private final EngineConfig config;
    private final HttpHandler handler;
    private final LoomServerEngine.Limits limits;
    private final Executor streamExecutor;
    private final Compression compression;
    private final String scheme;

    private final Hpack.Decoder decoder;
    private final Map<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    /** Streams whose handler has not finished yet (what MAX_CONCURRENT_STREAMS really bounds). */
    private final AtomicInteger activeHandlers = new AtomicInteger();
    private int lastStreamId;
    private volatile boolean goAwaySent;

    // ---- writing: one lock for the socket and for both send windows ----
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Condition windowOpened = writeLock.newCondition();
    private final Hpack.Encoder encoder = new Hpack.Encoder();
    private long sendWindow = DEFAULT_WINDOW;
    private int peerInitialWindow = DEFAULT_WINDOW;
    private int peerMaxFrameSize = MAX_FRAME_SIZE;

    // ---- abuse accounting (reader thread only) ----
    private long abuseWindowStart = System.nanoTime();
    private int resetsInWindow;
    private int controlFramesInWindow;

    private final byte[] frameHeader = new byte[9];

    Http2Connection(Connection connection, InputStream in, EngineConfig config, HttpHandler handler,
                    LoomServerEngine.Limits limits, Executor streamExecutor, Compression compression,
                    boolean secure) {
        this.connection = connection;
        this.in = in;
        this.config = config;
        this.handler = handler;
        this.limits = limits;
        this.streamExecutor = streamExecutor;
        this.compression = compression;
        this.scheme = secure ? "https" : "http";
        this.decoder = new Hpack.Decoder(HEADER_TABLE_SIZE, limits.maxHeaderBytes());
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Serves the connection until it ends.
     *
     * @param prefaceConsumed bytes of the client preface already read by the caller
     */
    void run(int prefaceConsumed) throws IOException {
        try {
            connection.readTimeout(limits.headerTimeout().toMillis());
            byte[] rest = readExactly(PREFACE.length - prefaceConsumed);
            for (int i = 0; i < rest.length; i++) {
                if (rest[i] != PREFACE[prefaceConsumed + i]) {
                    return; // not HTTP/2 after all
                }
            }
            sendSettings();
            connection.readTimeout(limits.idleTimeout().toMillis());
            boolean settingsSeen = false;
            while (true) {
                int length;
                int type;
                int flags;
                int streamId;
                try {
                    readFully(frameHeader, 0, 9);
                } catch (SocketTimeoutException quiet) {
                    if (activeHandlers.get() > 0) {
                        continue; // requests are still running; give them time
                    }
                    goAway(NO_ERROR);
                    return;
                }
                length = ((frameHeader[0] & 0xff) << 16) | ((frameHeader[1] & 0xff) << 8) | (frameHeader[2] & 0xff);
                type = frameHeader[3] & 0xff;
                flags = frameHeader[4] & 0xff;
                streamId = ((frameHeader[5] & 0x7f) << 24) | ((frameHeader[6] & 0xff) << 16)
                    | ((frameHeader[7] & 0xff) << 8) | (frameHeader[8] & 0xff);
                if (length > MAX_FRAME_SIZE) {
                    throw new ConnectionError(FRAME_SIZE_ERROR, "frame larger than SETTINGS_MAX_FRAME_SIZE");
                }
                if (!settingsSeen) {
                    if (type != SETTINGS || (flags & FLAG_ACK) != 0) {
                        throw new ConnectionError(PROTOCOL_ERROR, "the first frame must be SETTINGS");
                    }
                    settingsSeen = true;
                }
                byte[] payload = readExactly(length);
                switch (type) {
                    case DATA -> onData(streamId, flags, payload);
                    case HEADERS -> onHeaders(streamId, flags, payload);
                    case PRIORITY -> onPriority(streamId, payload);
                    case RST_STREAM -> onRstStream(streamId, payload);
                    case SETTINGS -> onSettings(streamId, flags, payload);
                    case PUSH_PROMISE -> throw new ConnectionError(PROTOCOL_ERROR, "clients cannot push");
                    case PING -> onPing(streamId, flags, payload);
                    case GOAWAY -> {
                        if (streamId != 0) {
                            throw new ConnectionError(PROTOCOL_ERROR, "GOAWAY on a stream");
                        }
                        awaitHandlers();
                        return;
                    }
                    case WINDOW_UPDATE -> onWindowUpdate(streamId, payload);
                    case CONTINUATION -> throw new ConnectionError(PROTOCOL_ERROR, "unexpected CONTINUATION");
                    default -> controlFrame(); // unknown frame types are ignored (RFC 9113 §4.1), but not free
                }
                if (goAwaySent && activeHandlers.get() == 0) {
                    return;
                }
            }
        } catch (ConnectionError e) {
            log.debug("HTTP/2 connection error: {}", e.getMessage());
            goAway(e.code);
            connection.lingeringClose(); // so the GOAWAY is not lost to a TCP reset
        } catch (Hpack.HpackException e) {
            log.debug("HTTP/2 header compression error: {}", e.getMessage());
            goAway(COMPRESSION_ERROR);
            connection.lingeringClose();
        } finally {
            // whatever the reason, nothing more will be read: release every waiting request
            for (Http2Stream stream : streams.values()) {
                stream.abort();
            }
            writeLock.lock();
            try {
                windowOpened.signalAll();
            } finally {
                writeLock.unlock();
            }
        }
    }

    /** Server shutdown: stop accepting new streams and let the running ones finish. */
    void shutdown() {
        goAway(NO_ERROR);
    }

    /** True when no request is being served. */
    boolean quiescent() {
        return activeHandlers.get() == 0;
    }

    private void awaitHandlers() {
        long deadline = System.nanoTime() + limits.readTimeout().toNanos();
        while (activeHandlers.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void readFully(byte[] target, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(target, offset + done, length - done);
            if (read < 0) {
                throw new EOFException("HTTP/2 peer closed the connection");
            }
            done += read;
        }
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] bytes = new byte[length];
        readFully(bytes, 0, length);
        return bytes;
    }

    /** Frames that make us work without carrying a request: bounded per time window. */
    private void controlFrame() throws ConnectionError {
        rollAbuseWindow();
        if (++controlFramesInWindow > MAX_CONTROL_FRAMES_PER_WINDOW) {
            throw new ConnectionError(ENHANCE_YOUR_CALM, "too many control frames");
        }
    }

    private void rollAbuseWindow() {
        long now = System.nanoTime();
        if (now - abuseWindowStart > ABUSE_WINDOW_NANOS) {
            abuseWindowStart = now;
            resetsInWindow = 0;
            controlFramesInWindow = 0;
        }
    }

    // ---- HEADERS (+ CONTINUATION) ----

    private void onHeaders(int streamId, int flags, byte[] payload) throws IOException, Hpack.HpackException {
        if (streamId == 0 || (streamId & 1) == 0) {
            throw new ConnectionError(PROTOCOL_ERROR, "HEADERS on an invalid stream id");
        }
        int offset = 0;
        int end = payload.length;
        if ((flags & FLAG_PADDED) != 0) {
            if (end < 1) {
                throw new ConnectionError(FRAME_SIZE_ERROR, "truncated HEADERS");
            }
            int padding = payload[0] & 0xff;
            offset = 1;
            end -= padding;
        }
        if ((flags & FLAG_PRIORITY) != 0) {
            offset += 5; // stream dependency + weight: deprecated, ignored
        }
        if (offset > end) {
            throw new ConnectionError(PROTOCOL_ERROR, "padding larger than the frame");
        }
        byte[] block = payload;
        int blockOffset = offset;
        int blockLength = end - offset;
        if ((flags & FLAG_END_HEADERS) == 0) {
            // The block continues in CONTINUATION frames, which must follow immediately.
            ByteArrayOutputStream collected = new ByteArrayOutputStream(Math.max(256, blockLength * 2));
            collected.write(payload, offset, blockLength);
            int frames = 0;
            while (true) {
                readFully(frameHeader, 0, 9);
                int length = ((frameHeader[0] & 0xff) << 16) | ((frameHeader[1] & 0xff) << 8) | (frameHeader[2] & 0xff);
                int id = ((frameHeader[5] & 0x7f) << 24) | ((frameHeader[6] & 0xff) << 16)
                    | ((frameHeader[7] & 0xff) << 8) | (frameHeader[8] & 0xff);
                if ((frameHeader[3] & 0xff) != CONTINUATION || id != streamId) {
                    throw new ConnectionError(PROTOCOL_ERROR, "header block interrupted");
                }
                if (length > MAX_FRAME_SIZE) {
                    throw new ConnectionError(FRAME_SIZE_ERROR, "frame larger than SETTINGS_MAX_FRAME_SIZE");
                }
                // bound the block while it is still arriving, and the number of frames it may be cut into
                if (collected.size() + length > limits.maxHeaderBytes() || ++frames > 32) {
                    throw new ConnectionError(ENHANCE_YOUR_CALM, "header block too large");
                }
                collected.write(readExactly(length), 0, length);
                if ((frameHeader[4] & FLAG_END_HEADERS) != 0) {
                    break;
                }
            }
            block = collected.toByteArray();
            blockOffset = 0;
            blockLength = block.length;
        }
        if (blockLength > limits.maxHeaderBytes()) {
            throw new ConnectionError(ENHANCE_YOUR_CALM, "header block too large");
        }
        // Always decode: the compression state is shared by every stream on the connection.
        List<Hpack.Field> fields = decoder.decode(block, blockOffset, blockLength);
        boolean endStream = (flags & FLAG_END_STREAM) != 0;

        Http2Stream existing = streams.get(streamId);
        if (existing != null) {
            // a second HEADERS on an open stream can only be trailers, and those end the request
            if (!endStream || existing.requestEnded() || !existing.endOfRequest()) {
                resetStream(streamId, PROTOCOL_ERROR);
                existing.abort();
            }
            return;
        }
        if (streamId <= lastStreamId) {
            // a stream we already closed: tolerated (frames may have been in flight), but counted
            controlFrame();
            return;
        }
        lastStreamId = streamId;
        if (goAwaySent) {
            return; // shutting down: the client was told not to open new streams
        }
        if (activeHandlers.get() >= limits.maxConcurrentStreams()) {
            resetStream(streamId, REFUSED_STREAM);
            countReset();
            return;
        }
        RequestHead head = Http2Requests.toRequestHead(fields, scheme);
        if (head == null) {
            resetStream(streamId, PROTOCOL_ERROR); // malformed request (RFC 9113 §8.1.1)
            countReset();
            return;
        }
        Http2Stream stream = new Http2Stream(this, streamId, head, Http2Requests.contentLength(fields),
            peerInitialWindow, config.maxBodyBytes());
        if (endStream && !stream.endOfRequest()) {
            resetStream(streamId, PROTOCOL_ERROR); // a content-length was declared but no body follows
            countReset();
            return;
        }
        streams.put(streamId, stream);
        activeHandlers.incrementAndGet();
        try {
            streamExecutor.execute(() -> serve(stream));
        } catch (RuntimeException rejected) {
            activeHandlers.decrementAndGet();
            streams.remove(streamId);
            resetStream(streamId, REFUSED_STREAM);
        }
    }

    /** Runs one request on its own thread. */
    private void serve(Http2Stream stream) {
        stream.thread(Thread.currentThread());
        LoomHttpRequest request = new LoomHttpRequest(stream, stream.head(), config.maxBodyBytes(), "HTTP/2.0");
        Http2Response response = new Http2Response(this, stream, config, compression);
        try {
            try {
                handler.handle(request, response);
            } catch (Exception e) {
                log.error("Request pipeline failed", e);
                if (!response.isCommitted()) {
                    response.status(500).contentType("text/plain; charset=utf-8").send("Internal server error");
                } else {
                    stream.reset(INTERNAL_ERROR);
                }
            }
            response.end();
        } catch (RuntimeException e) {
            log.debug("HTTP/2 stream {} ended abnormally: {}", stream.id(), e.toString());
            stream.reset(INTERNAL_ERROR);
        } finally {
            stream.finish();
            streams.remove(stream.id());
            activeHandlers.decrementAndGet();
            Thread.interrupted(); // a cancelled stream must not leak its interrupt to the pool
        }
    }

    // ---- DATA ----

    private void onData(int streamId, int flags, byte[] payload) throws IOException {
        if (streamId == 0) {
            throw new ConnectionError(PROTOCOL_ERROR, "DATA on stream 0");
        }
        int offset = 0;
        int end = payload.length;
        if ((flags & FLAG_PADDED) != 0) {
            if (end < 1) {
                throw new ConnectionError(FRAME_SIZE_ERROR, "truncated DATA");
            }
            int padding = payload[0] & 0xff;
            offset = 1;
            end -= padding;
            if (offset > end) {
                throw new ConnectionError(PROTOCOL_ERROR, "padding larger than the frame");
            }
        }
        int dataLength = end - offset;
        boolean endStream = (flags & FLAG_END_STREAM) != 0;
        if (dataLength == 0 && !endStream) {
            controlFrame(); // empty DATA frames carry nothing
        }
        Http2Stream stream = streams.get(streamId);
        if (stream == null || stream.requestEnded()) {
            if (streamId > lastStreamId) {
                throw new ConnectionError(PROTOCOL_ERROR, "DATA on an idle stream");
            }
            // closed stream: the bytes still used connection window, give it back
            creditConnectionWindow(payload.length);
            if (stream != null) {
                resetStream(streamId, STREAM_CLOSED);
                stream.abort();
            }
            return;
        }
        // padding is flow controlled too but never reaches the application
        creditWindows(streamId, payload.length - dataLength, true);
        if (!stream.offer(payload, offset, dataLength, endStream)) {
            resetStream(streamId, stream.requestEnded() ? PROTOCOL_ERROR : FLOW_CONTROL_ERROR);
            stream.abort();
        }
    }

    // ---- everything else ----

    private void onPriority(int streamId, byte[] payload) throws ConnectionError {
        if (streamId == 0) {
            throw new ConnectionError(PROTOCOL_ERROR, "PRIORITY on stream 0");
        }
        if (payload.length != 5) {
            resetStream(streamId, FRAME_SIZE_ERROR);
        }
        controlFrame();
    }

    private void onRstStream(int streamId, byte[] payload) throws ConnectionError {
        if (streamId == 0 || streamId > lastStreamId) {
            throw new ConnectionError(PROTOCOL_ERROR, "RST_STREAM on an idle stream");
        }
        if (payload.length != 4) {
            throw new ConnectionError(FRAME_SIZE_ERROR, "RST_STREAM must be 4 bytes");
        }
        Http2Stream stream = streams.get(streamId);
        if (stream != null) {
            stream.abort(); // the handler is interrupted, but its slot stays taken until it has returned
        }
        countReset();
    }

    /** Streams the client opens only to cancel are the "rapid reset" pattern. */
    private void countReset() throws ConnectionError {
        rollAbuseWindow();
        if (++resetsInWindow > MAX_RESETS_PER_WINDOW) {
            throw new ConnectionError(ENHANCE_YOUR_CALM, "too many reset streams");
        }
    }

    private void onSettings(int streamId, int flags, byte[] payload) throws IOException {
        if (streamId != 0) {
            throw new ConnectionError(PROTOCOL_ERROR, "SETTINGS on a stream");
        }
        if ((flags & FLAG_ACK) != 0) {
            if (payload.length != 0) {
                throw new ConnectionError(FRAME_SIZE_ERROR, "SETTINGS ack with a payload");
            }
            return;
        }
        if (payload.length % 6 != 0) {
            throw new ConnectionError(FRAME_SIZE_ERROR, "malformed SETTINGS");
        }
        controlFrame();
        writeLock.lock();
        try {
            for (int i = 0; i < payload.length; i += 6) {
                int id = ((payload[i] & 0xff) << 8) | (payload[i + 1] & 0xff);
                long value = ((payload[i + 2] & 0xffL) << 24) | ((payload[i + 3] & 0xff) << 16)
                    | ((payload[i + 4] & 0xff) << 8) | (payload[i + 5] & 0xff);
                switch (id) {
                    case 0x2 -> {
                        if (value > 1) {
                            throw new ConnectionError(PROTOCOL_ERROR, "invalid ENABLE_PUSH");
                        }
                    }
                    case 0x4 -> {
                        if (value > Integer.MAX_VALUE) {
                            throw new ConnectionError(FLOW_CONTROL_ERROR, "invalid INITIAL_WINDOW_SIZE");
                        }
                        int delta = (int) value - peerInitialWindow;
                        peerInitialWindow = (int) value;
                        for (Http2Stream stream : streams.values()) {
                            if (!stream.adjustSendWindow(delta)) {
                                throw new ConnectionError(FLOW_CONTROL_ERROR, "window overflow");
                            }
                        }
                        windowOpened.signalAll();
                    }
                    case 0x5 -> {
                        if (value < MAX_FRAME_SIZE || value > 16_777_215) {
                            throw new ConnectionError(PROTOCOL_ERROR, "invalid MAX_FRAME_SIZE");
                        }
                        peerMaxFrameSize = MAX_FRAME_SIZE; // we never send more than the minimum anyway
                    }
                    default -> {
                        // header table size, max concurrent streams, max header list size, unknown: nothing to do
                    }
                }
            }
            frame(SETTINGS, FLAG_ACK, 0, new byte[0], 0, 0);
        } finally {
            writeLock.unlock();
        }
    }

    private void onPing(int streamId, int flags, byte[] payload) throws ConnectionError {
        if (streamId != 0) {
            throw new ConnectionError(PROTOCOL_ERROR, "PING on a stream");
        }
        if (payload.length != 8) {
            throw new ConnectionError(FRAME_SIZE_ERROR, "PING must be 8 bytes");
        }
        if ((flags & FLAG_ACK) != 0) {
            return;
        }
        controlFrame();
        writeLock.lock();
        try {
            frame(PING, FLAG_ACK, 0, payload, 0, 8);
        } finally {
            writeLock.unlock();
        }
    }

    private void onWindowUpdate(int streamId, byte[] payload) throws ConnectionError {
        if (payload.length != 4) {
            throw new ConnectionError(FRAME_SIZE_ERROR, "WINDOW_UPDATE must be 4 bytes");
        }
        int increment = ((payload[0] & 0x7f) << 24) | ((payload[1] & 0xff) << 16)
            | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        writeLock.lock();
        try {
            if (streamId == 0) {
                if (increment == 0) {
                    throw new ConnectionError(PROTOCOL_ERROR, "WINDOW_UPDATE of 0");
                }
                sendWindow += increment;
                if (sendWindow > Integer.MAX_VALUE) {
                    throw new ConnectionError(FLOW_CONTROL_ERROR, "window overflow");
                }
            } else {
                if (streamId > lastStreamId) {
                    throw new ConnectionError(PROTOCOL_ERROR, "WINDOW_UPDATE on an idle stream");
                }
                Http2Stream stream = streams.get(streamId);
                if (stream != null && (increment == 0 || !stream.adjustSendWindow(increment))) {
                    stream.abort();
                    frame(RST_STREAM, 0, streamId, intBytes(increment == 0 ? PROTOCOL_ERROR : FLOW_CONTROL_ERROR), 0, 4);
                }
            }
            windowOpened.signalAll();
        } finally {
            writeLock.unlock();
        }
        controlFrame();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private void sendSettings() {
        byte[] settings = new byte[18];
        setting(settings, 0, 0x3, limits.maxConcurrentStreams());   // MAX_CONCURRENT_STREAMS
        setting(settings, 6, 0x6, limits.maxHeaderBytes());         // MAX_HEADER_LIST_SIZE
        setting(settings, 12, 0x2, 0);                              // ENABLE_PUSH: we never push
        writeLock.lock();
        try {
            frame(SETTINGS, 0, 0, settings, 0, settings.length);
        } finally {
            writeLock.unlock();
        }
    }

    private static void setting(byte[] target, int offset, int id, int value) {
        target[offset] = (byte) (id >>> 8);
        target[offset + 1] = (byte) id;
        target[offset + 2] = (byte) (value >>> 24);
        target[offset + 3] = (byte) (value >>> 16);
        target[offset + 4] = (byte) (value >>> 8);
        target[offset + 5] = (byte) value;
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    /** Writes one frame. The caller holds {@link #writeLock}. */
    private void frame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        byte[] out = new byte[9 + length];
        out[0] = (byte) (length >>> 16);
        out[1] = (byte) (length >>> 8);
        out[2] = (byte) length;
        out[3] = (byte) type;
        out[4] = (byte) flags;
        out[5] = (byte) (streamId >>> 24);
        out[6] = (byte) (streamId >>> 16);
        out[7] = (byte) (streamId >>> 8);
        out[8] = (byte) streamId;
        System.arraycopy(payload, offset, out, 9, length);
        try {
            connection.write(out, 0, out.length);
        } catch (java.io.UncheckedIOException gone) {
            // the reader will notice the dead connection; writers just stop
        }
    }

    private void goAway(int errorCode) {
        if (goAwaySent && errorCode == NO_ERROR) {
            return;
        }
        goAwaySent = true;
        byte[] payload = new byte[8];
        System.arraycopy(intBytes(lastStreamId), 0, payload, 0, 4);
        System.arraycopy(intBytes(errorCode), 0, payload, 4, 4);
        writeLock.lock();
        try {
            frame(GOAWAY, 0, 0, payload, 0, 8);
        } finally {
            writeLock.unlock();
        }
    }

    void resetStream(int streamId, int errorCode) {
        writeLock.lock();
        try {
            frame(RST_STREAM, 0, streamId, intBytes(errorCode), 0, 4);
        } finally {
            writeLock.unlock();
        }
    }

    /** Gives the peer back connection-level receive window. */
    void creditConnectionWindow(int bytes) {
        if (bytes <= 0) {
            return;
        }
        writeLock.lock();
        try {
            frame(WINDOW_UPDATE, 0, 0, intBytes(bytes), 0, 4);
        } finally {
            writeLock.unlock();
        }
    }

    /** Gives the peer back receive window on the stream and on the connection, as data is consumed. */
    void creditWindows(int streamId, int bytes, boolean streamStillOpen) {
        if (bytes <= 0) {
            return;
        }
        writeLock.lock();
        try {
            frame(WINDOW_UPDATE, 0, 0, intBytes(bytes), 0, 4);
            if (streamStillOpen) {
                frame(WINDOW_UPDATE, 0, streamId, intBytes(bytes), 0, 4);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Sends a response head, split into CONTINUATION frames if it does not fit in one. */
    void sendHeaders(Http2Stream stream, HeaderWriter writer, boolean endStream) {
        writeLock.lock();
        try {
            encoder.reset();
            writer.write(encoder);
            byte[] block = encoder.toByteArray();
            int offset = 0;
            boolean first = true;
            do {
                int chunk = Math.min(peerMaxFrameSize, block.length - offset);
                boolean last = offset + chunk == block.length;
                int flags = (last ? FLAG_END_HEADERS : 0) | (first && endStream ? FLAG_END_STREAM : 0);
                frame(first ? HEADERS : CONTINUATION, flags, stream.id(), block, offset, chunk);
                offset += chunk;
                first = false;
            } while (offset < block.length);
        } finally {
            writeLock.unlock();
        }
    }

    /** Fills in a response header block. */
    interface HeaderWriter {
        void write(Hpack.Encoder encoder);
    }

    /**
     * Sends response body bytes, waiting for flow-control window as needed.
     *
     * @throws IOException if the stream was cancelled or the peer keeps its window shut past the write timeout
     */
    void sendData(Http2Stream stream, byte[] data, int offset, int length, boolean endStream) throws IOException {
        int sent = 0;
        long deadline = System.nanoTime() + limits.writeTimeout().toNanos();
        writeLock.lock();
        try {
            do {
                if (stream.cancelled()) {
                    throw new IOException("HTTP/2 stream cancelled by the client");
                }
                int remaining = length - sent;
                int allowed = (int) Math.min(Math.min(remaining, peerMaxFrameSize),
                    Math.min(sendWindow, stream.sendWindow()));
                if (allowed <= 0 && remaining > 0) {
                    long wait = deadline - System.nanoTime();
                    if (wait <= 0) {
                        throw new IOException("HTTP/2 peer did not open its flow-control window in time");
                    }
                    try {
                        windowOpened.awaitNanos(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while waiting for flow-control window");
                    }
                    continue;
                }
                boolean last = sent + allowed == length;
                frame(DATA, last && endStream ? FLAG_END_STREAM : 0, stream.id(), data, offset + sent, allowed);
                sendWindow -= allowed;
                stream.consumeSendWindow(allowed);
                sent += allowed;
                deadline = System.nanoTime() + limits.writeTimeout().toNanos();
            } while (sent < length);
        } finally {
            writeLock.unlock();
        }
    }

    String remoteAddress() {
        return connection.remoteAddress();
    }

    /** Sink for a streamed response body: buffered into DATA frames. */
    static final class DataOutputStream extends OutputStream {

        private final Http2Connection connection;
        private final Http2Stream stream;
        private final byte[] buffer = new byte[8 * 1024];
        private int count;
        private boolean closed;

        DataOutputStream(Http2Connection connection, Http2Stream stream) {
            this.connection = connection;
            this.stream = stream;
        }

        @Override
        public void write(int b) throws IOException {
            if (count == buffer.length) {
                flush();
            }
            buffer[count++] = (byte) b;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (length >= buffer.length) {
                flush();
                connection.sendData(stream, bytes, offset, length, false);
                return;
            }
            if (length > buffer.length - count) {
                flush();
            }
            System.arraycopy(bytes, offset, buffer, count, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            if (count > 0) {
                int n = count;
                count = 0;
                connection.sendData(stream, buffer, 0, n, false);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            int n = count;
            count = 0;
            connection.sendData(stream, buffer, 0, n, true); // the last (possibly empty) frame ends the stream
            stream.responseEnded();
        }
    }
}
