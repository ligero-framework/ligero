package com.ligero.server.loom;

import com.ligero.http.HttpResponse;
import com.ligero.spi.EngineConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * {@link HttpResponse} that assembles the status line, headers and (when it
 * fits) the body in the connection's scratch buffer and sends them with a
 * single write. Streaming responses use chunked transfer encoding.
 */
final class LoomHttpResponse implements HttpResponse {

    private static final byte[] CRLF = {'\r', '\n'};

    private final Connection connection;
    private final RequestHead head;
    private final EngineConfig config;
    private final boolean headRequest;

    private String contentType = "text/plain; charset=utf-8";
    private int status = 200;
    private String[] headerNames = new String[8];
    private String[] headerValues = new String[8];
    private int headerCount;
    private boolean closeRequested;
    private boolean committed;
    private boolean keepAlive;
    private OutputStream stream;

    private byte[] buf;
    private int length;

    LoomHttpResponse(Connection connection, RequestHead head, EngineConfig config) {
        this.connection = connection;
        this.head = head;
        this.config = config;
        this.headRequest = "HEAD".equals(head.method);
    }

    @Override
    public HttpResponse status(int statusCode) {
        requireNotCommitted();
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("Not an HTTP status code: " + statusCode);
        }
        this.status = statusCode;
        return this;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public HttpResponse header(String name, String value) {
        requireNotCommitted();
        validate(name, value);
        // Framing is the engine's job: letting an application set these could
        // produce a response two parties delimit differently.
        if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) {
            return this;
        }
        if (name.equalsIgnoreCase("Connection")) {
            closeRequested |= value.equalsIgnoreCase("close");
            return this;
        }
        if (name.equalsIgnoreCase("Content-Type")) {
            this.contentType = value;
            return this;
        }
        boolean repeatable = name.equalsIgnoreCase("Set-Cookie") || name.equalsIgnoreCase("Vary");
        if (!repeatable) {
            for (int i = 0; i < headerCount; i++) {
                if (headerNames[i].equalsIgnoreCase(name)) {
                    headerValues[i] = value;
                    return this;
                }
            }
        }
        if (headerCount == headerNames.length) {
            headerNames = java.util.Arrays.copyOf(headerNames, headerCount * 2);
            headerValues = java.util.Arrays.copyOf(headerValues, headerCount * 2);
        }
        headerNames[headerCount] = name;
        headerValues[headerCount] = value;
        headerCount++;
        return this;
    }

    @Override
    public HttpResponse contentType(String contentType) {
        requireNotCommitted();
        validate("Content-Type", contentType);
        this.contentType = contentType;
        return this;
    }

    @Override
    public HttpResponse send(String body) {
        requireNotCommitted();
        byte[] bytes = body == null || body.isEmpty() ? null : body.getBytes(StandardCharsets.UTF_8);
        boolean gzipped = false;
        if (bytes != null && config.gzip() && head.acceptsGzip && bytes.length >= config.gzipMinBytes()) {
            bytes = gzip(bytes);
            gzipped = true;
        }
        int bodyLength = bytes == null ? 0 : bytes.length;
        beginHead();
        if (gzipped) {
            ascii("Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n");
        }
        if (hasBody()) {
            ascii("Content-Type: ");
            ascii(contentType);
            raw(CRLF);
            ascii("Content-Length: ");
            number(bodyLength);
            raw(CRLF);
        }
        raw(CRLF);
        if (bytes != null && hasBody() && !headRequest) {
            if (length + bodyLength <= 64 * 1024) {
                raw(bytes);
                flushHead();
            } else {
                flushHead();
                connection.write(bytes, 0, bodyLength);
            }
        } else {
            flushHead();
        }
        return this;
    }

    @Override
    public HttpResponse json(Object object) {
        if (config.bodyMapper() == null) {
            throw new IllegalStateException(
                "No BodyMapper found. Add ligero-json (or another BodyMapper implementation) to the classpath.");
        }
        contentType = "application/json";
        return send(config.bodyMapper().writeJson(object));
    }

    @Override
    public OutputStream getOutputStream() {
        requireNotCommitted();
        boolean chunked = !head.http10;
        if (!chunked) {
            closeRequested = true; // HTTP/1.0 has no chunking: the close delimits the body
        }
        beginHead();
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
        if (headRequest || !hasBody()) {
            stream = OutputStream.nullOutputStream();
        } else {
            stream = chunked ? new ChunkedOutputStream(connection) : new RawOutputStream(connection);
        }
        return stream;
    }

    @Override
    public HttpResponse redirect(String url, int redirectStatus) {
        if (redirectStatus < 300 || redirectStatus > 399) {
            throw new IllegalArgumentException("Redirect status must be 3xx, got " + redirectStatus);
        }
        header("Location", url);
        status = redirectStatus;
        end();
        return this;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void end() {
        if (!committed) {
            beginHead();
            if (hasBody()) {
                ascii("Content-Length: 0\r\n");
            }
            raw(CRLF);
            flushHead();
        } else if (stream != null) {
            OutputStream open = stream;
            stream = null;
            try {
                open.close(); // writes the terminating chunk if the handler did not
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    /** Whether the connection may serve another request after this response. */
    boolean keepAlive() {
        return committed && keepAlive;
    }

    // ------------------------------------------------------------------

    /** 1xx, 204 and 304 responses never carry a body (nor the headers describing one). */
    private boolean hasBody() {
        return status >= 200 && status != 204 && status != 304;
    }

    private void beginHead() {
        // Decide the connection's fate before the first byte goes out: the
        // rest of the request body must be consumed (or the connection closed)
        // so the next request starts where we expect it to.
        keepAlive = head.keepAlive && !closeRequested && !connection.stopping() && connection.finishBody();
        committed = true;
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

    /**
     * A header name must be a token and a value must not contain line breaks
     * or other control characters — otherwise data placed in a header could
     * end it early and inject headers or a whole second response.
     */
    private static void validate(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Header name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            if (!HeadParser.isTokenChar(name.charAt(i))) {
                throw new IllegalArgumentException("Illegal character in header name");
            }
        }
        if (value == null) {
            throw new IllegalArgumentException("Header value must not be null: " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalArgumentException("Illegal character in the value of header " + name);
            }
        }
    }

    private void requireNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Response has already been committed");
        }
    }

    private static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length / 2 + 16);
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 418 -> "I'm a teapot";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> status < 200 ? "Informational" : status < 300 ? "Success"
                : status < 400 ? "Redirection" : status < 500 ? "Client Error" : "Server Error";
        };
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
