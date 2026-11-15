package com.ligero.server.pluma;

import com.ligero.spi.EngineConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 response: assembles the status line, headers and (when it fits)
 * the body in the connection's scratch buffer and sends them with a single
 * write. Streaming responses use chunked transfer encoding.
 */
final class PlumaHttpResponse extends AbstractResponse {

    private static final byte[] CRLF = {'\r', '\n'};

    private final Connection connection;
    private final RequestHead head;
    private boolean closeRequested;
    private boolean keepAlive;

    private byte[] buf;
    private int length;

    PlumaHttpResponse(Connection connection, RequestHead head, EngineConfig config, Compression compression) {
        super(config, compression, "HEAD".equals(head.method));
        this.connection = connection;
        this.head = head;
    }

    @Override
    String acceptEncoding() {
        return head.header("Accept-Encoding");
    }

    @Override
    void closeRequested() {
        closeRequested = true;
    }

    @Override
    void commit(byte[] body, String contentEncoding) {
        int bodyLength = body == null ? 0 : body.length;
        beginHead(contentEncoding);
        if (hasBody()) {
            if (body != null) {
                ascii("Content-Type: ");
                ascii(contentType);
                raw(CRLF);
            }
            ascii("Content-Length: ");
            number(bodyLength);
            raw(CRLF);
        }
        raw(CRLF);
        if (body != null && !isHeadRequest()) {
            if (length + bodyLength <= 64 * 1024) {
                raw(body);
                flushHead();
            } else {
                flushHead();
                connection.write(body, 0, bodyLength);
            }
        } else {
            flushHead();
        }
    }

    @Override
    OutputStream commitStreaming(String contentEncoding) {
        boolean chunked = !head.http10;
        if (!chunked) {
            closeRequested = true; // HTTP/1.0 has no chunking: the close delimits the body
        }
        beginHead(contentEncoding);
        if (hasBody()) {
            ascii("Content-Type: ");
            ascii(contentType);
            raw(CRLF);
            if (chunked) {
                ascii("Transfer-Encoding: chunked\r\n");
            }
        }
        raw(CRLF);
        flushHead();
        return chunked ? new ChunkedOutputStream(connection) : new RawOutputStream(connection);
    }

    /** Whether the connection may serve another request after this response. */
    boolean keepAlive() {
        return committed && keepAlive;
    }

    // ------------------------------------------------------------------

    private void beginHead(String contentEncoding) {
        // Decide the connection's fate before the first byte goes out: the
        // rest of the request body must be consumed (or the connection closed)
        // so the next request starts where we expect it to.
        keepAlive = head.keepAlive && !closeRequested && !connection.stopping() && connection.finishBody();
        buf = connection.outBuf;
        length = 0;
        ascii("HTTP/1.1 ");
        number(status);
        raw((byte) ' ');
        ascii(reason(status));
        raw(CRLF);
        raw(HttpDate.headerLine());
        for (int i = 0; i < headerCount; i++) {
            ascii(headerNames[i]);
            raw((byte) ':');
            raw((byte) ' ');
            ascii(headerValues[i]);
            raw(CRLF);
        }
        if (contentEncoding != null) {
            ascii("Content-Encoding: ");
            ascii(contentEncoding);
            ascii("\r\nVary: Accept-Encoding\r\n");
        }
        if (!keepAlive) {
            ascii("Connection: close\r\n");
        }
    }

    private void flushHead() {
        connection.write(buf, 0, length);
        if (buf.length <= 64 * 1024) {
            connection.outBuf = buf; // keep a grown buffer for the next response
        }
        buf = null;
    }

    private void ensure(int extra) {
        if (length + extra > buf.length) {
            buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, length + extra));
        }
    }

    private void raw(byte b) {
        ensure(1);
        buf[length++] = b;
    }

    private void raw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, length, bytes.length);
        length += bytes.length;
    }

    /** Header text: ISO-8859-1, anything beyond it replaced. */
    private void ascii(String text) {
        int n = text.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            buf[length++] = c < 0x100 ? (byte) c : (byte) '?';
        }
    }

    private void number(int value) {
        if (value == 0) {
            raw((byte) '0');
            return;
        }
        ensure(11);
        int start = length;
        for (int v = value; v > 0; v /= 10) {
            buf[length++] = (byte) ('0' + v % 10);
        }
        for (int i = start, j = length - 1; i < j; i++, j--) {
            byte swap = buf[i];
            buf[i] = buf[j];
            buf[j] = swap;
        }
    }

    /** Body stream using chunked transfer encoding; {@code flush()} pushes a chunk to the client. */
    private static final class ChunkedOutputStream extends OutputStream {

        private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};
        private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        private static final int PREFIX = 10; // room for the size line: 8 hex digits + CRLF

        private final Connection connection;
        private final byte[] chunk = new byte[PREFIX + 8 * 1024 + 2];
        private int count;
        private boolean closed;

        ChunkedOutputStream(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            if (count == chunk.length - PREFIX - 2) {
                flushChunk();
            }
            chunk[PREFIX + count++] = (byte) b;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            int capacity = chunk.length - PREFIX - 2;
            int done = 0;
            while (done < length) {
                if (count == capacity) {
                    flushChunk();
                }
                int n = Math.min(capacity - count, length - done);
                System.arraycopy(bytes, offset + done, chunk, PREFIX + count, n);
                count += n;
                done += n;
            }
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            flushChunk();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            flushChunk();
            closed = true;
            send(LAST_CHUNK, 0, LAST_CHUNK.length);
        }

        /** Size line, data and trailing CRLF go out as one write. */
        private void flushChunk() throws IOException {
            if (count == 0) {
                return;
            }
            int start = PREFIX - 2;
            chunk[start] = '\r';
            chunk[start + 1] = '\n';
            for (int v = count; v > 0; v >>>= 4) {
                chunk[--start] = HEX[v & 0xf];
            }
            int end = PREFIX + count;
            chunk[end] = '\r';
            chunk[end + 1] = '\n';
            send(chunk, start, end + 2 - start);
            count = 0;
        }

        private void send(byte[] bytes, int offset, int length) throws IOException {
            try {
                connection.write(bytes, offset, length);
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
        }
    }

    /** Body stream for HTTP/1.0 clients: bytes as they are, ended by closing the connection. */
    private static final class RawOutputStream extends OutputStream {

        private final Connection connection;

        RawOutputStream(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                connection.write(bytes, offset, length);
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }
}
