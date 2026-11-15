package com.ligero.server.loom;

import com.ligero.http.PayloadTooLargeException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One HTTP/2 request/response exchange. The connection's reader thread feeds
 * it body frames; the request's own thread consumes them — and only then is
 * the peer granted more flow-control window, so an application that reads
 * slowly (or not at all) holds at most one window of data in memory.
 */
final class Http2Stream implements BodySource {

    private static final int RECEIVE_WINDOW = 65_535;
    private static final long BODY_WAIT_SECONDS = 30;

    private final Http2Connection connection;
    private final int id;
    private final RequestHead head;
    private final long declaredLength;   // -1 when the request has no content-length
    private final long maxBodyBytes;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    private final ArrayDeque<byte[]> inbox = new ArrayDeque<>();
    private int buffered;
    private long received;
    private int receiveWindow = RECEIVE_WINDOW;
    private boolean requestEnded;
    private boolean tooLarge;
    private volatile boolean aborted;
    private volatile boolean responseEnded;
    private volatile Thread thread;

    /** Guarded by the connection's write lock. */
    private long sendWindow;

    Http2Stream(Http2Connection connection, int id, RequestHead head, long declaredLength,
                int initialSendWindow, long maxBodyBytes) {
        this.connection = connection;
        this.id = id;
        this.head = head;
        this.declaredLength = declaredLength;
        this.sendWindow = initialSendWindow;
        this.maxBodyBytes = maxBodyBytes;
    }

    int id() {
        return id;
    }

    RequestHead head() {
        return head;
    }

    void thread(Thread thread) {
        this.thread = thread;
    }

    // ---- called by the connection's reader thread ----------------------

    /**
     * Accepts body bytes from a DATA frame. Returns false when the frame
     * violates flow control or the declared content length.
     */
    boolean offer(byte[] payload, int offset, int length, boolean endStream) {
        lock.lock();
        try {
            if (requestEnded) {
                return false;
            }
            if (length > receiveWindow) {
                return false; // the peer sent more than we allowed
            }
            receiveWindow -= length;
            received += length;
            if (declaredLength >= 0 && (received > declaredLength || (endStream && received != declaredLength))) {
                return false; // body and content-length disagree
            }
            if (length > 0) {
                if (tooLarge || received > maxBodyBytes) {
                    // Past the limit nothing is kept. The window is handed straight
                    // back so the client can finish and read its 413.
                    tooLarge = true;
                    receiveWindow += length;
                    connection.creditWindows(id, length, true);
                } else {
                    byte[] chunk = new byte[length];
                    System.arraycopy(payload, offset, chunk, 0, length);
                    inbox.add(chunk);
                    buffered += length;
                }
            }
            if (endStream) {
                requestEnded = true;
            }
            arrived.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The request ended without a final DATA frame. False when its length contradicts content-length. */
    boolean endOfRequest() {
        lock.lock();
        try {
            requestEnded = true;
            arrived.signalAll();
            return declaredLength < 0 || received == declaredLength;
        } finally {
            lock.unlock();
        }
    }

    boolean requestEnded() {
        lock.lock();
        try {
            return requestEnded;
        } finally {
            lock.unlock();
        }
    }

    /** The exchange is over from the peer's side (reset, protocol error, connection gone). */
    void abort() {
        aborted = true;
        lock.lock();
        try {
            arrived.signalAll();
        } finally {
            lock.unlock();
        }
        Thread running = thread;
        if (running != null) {
            running.interrupt(); // stop work nobody is waiting for
        }
    }

    boolean cancelled() {
        return aborted;
    }

    // ---- send window (connection write lock held) ----------------------

    long sendWindow() {
        return sendWindow;
    }

    void consumeSendWindow(int bytes) {
        sendWindow -= bytes;
    }

    /** Applies a WINDOW_UPDATE or a SETTINGS change; false on overflow. */
    boolean adjustSendWindow(int delta) {
        sendWindow += delta;
        return sendWindow <= Integer.MAX_VALUE;
    }

    // ---- called by the request's thread --------------------------------

    void reset(int errorCode) {
        aborted = true;
        responseEnded = true;
        connection.resetStream(id, errorCode);
    }

    void responseEnded() {
        responseEnded = true;
    }

    /** The handler has returned: release whatever the request still holds. */
    void finish() {
        thread = null;
        int unread;
        boolean ended;
        lock.lock();
        try {
            unread = buffered;
            buffered = 0;
            inbox.clear();
            ended = requestEnded;
        } finally {
            lock.unlock();
        }
        if (!ended && !aborted) {
            // we answered without needing the rest of the body: tell the client to stop sending it
            connection.resetStream(id, Http2Connection.NO_ERROR);
        }
        connection.creditWindows(id, unread, false);
    }

    /** Next piece of the body, or {@code null} at its end. Grants the peer window for what it returns. */
    private byte[] take() throws IOException {
        byte[] chunk;
        boolean stillOpen;
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BODY_WAIT_SECONDS);
            while (inbox.isEmpty()) {
                if (tooLarge) {
                    throw new PayloadTooLargeException(maxBodyBytes);
                }
                if (requestEnded) {
                    return null;
                }
                if (aborted) {
                    throw new IOException("HTTP/2 stream cancelled by the client");
                }
                long wait = deadline - System.nanoTime();
                if (wait <= 0) {
                    throw new IOException("timed out waiting for the request body");
                }
                try {
                    arrived.awaitNanos(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading the request body");
                }
            }
            chunk = inbox.poll();
            buffered -= chunk.length;
            receiveWindow += chunk.length;
            stillOpen = !requestEnded;
        } finally {
            lock.unlock();
        }
        connection.creditWindows(id, chunk.length, stillOpen);
        return chunk;
    }

    @Override
    public byte[] readBody(long maxBytes) {
        if (declaredLength > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        ByteArrayOutputStream collected = new ByteArrayOutputStream(
            declaredLength > 0 ? (int) Math.min(declaredLength, 64 * 1024) : 256);
        try {
            for (byte[] chunk = take(); chunk != null; chunk = take()) {
                if (collected.size() + (long) chunk.length > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                collected.write(chunk, 0, chunk.length);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read request body", e);
        }
        return collected.toByteArray();
    }

    @Override
    public InputStream openBodyStream(long maxBytes) {
        if (declaredLength > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        return new InputStream() {
            private byte[] current;
            private int position;
            private long delivered;
            private boolean eof;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (eof) {
                    return -1;
                }
                if (length == 0) {
                    return 0;
                }
                if (current == null || position == current.length) {
                    current = take();
                    position = 0;
                    if (current == null) {
                        eof = true;
                        return -1;
                    }
                    delivered += current.length;
                    if (delivered > maxBytes) {
                        throw new PayloadTooLargeException(maxBytes);
                    }
                }
                int n = Math.min(length, current.length - position);
                System.arraycopy(current, position, target, offset, n);
                position += n;
                return n;
            }
        };
    }

    @Override
    public String remoteAddress() {
        return connection.remoteAddress();
    }
}
