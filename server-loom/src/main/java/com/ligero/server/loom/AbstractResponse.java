package com.ligero.server.loom;

import com.ligero.http.HttpResponse;
import com.ligero.spi.ContentEncoder;
import com.ligero.spi.EngineConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Everything about a response that does not depend on the wire protocol:
 * status, validated headers, the content type, compression and the committed
 * state. Subclasses put it on the wire as HTTP/1.1 or HTTP/2.
 */
abstract class AbstractResponse implements HttpResponse {

    protected final EngineConfig config;
    private final Compression compression;
    private final boolean headRequest;

    protected String contentType = "text/plain; charset=utf-8";
    protected int status = 200;
    protected String[] headerNames = new String[8];
    protected String[] headerValues = new String[8];
    protected int headerCount;
    protected boolean committed;
    private boolean contentEncodingSet;
    private OutputStream stream;

    AbstractResponse(EngineConfig config, Compression compression, boolean headRequest) {
        this.config = config;
        this.compression = compression;
        this.headRequest = headRequest;
    }

    // ---- what a transport provides ------------------------------------

    /** The request's {@code Accept-Encoding} header, or {@code null}. */
    abstract String acceptEncoding();

    /** The application asked for the connection to be closed after this response. */
    abstract void closeRequested();

    /**
     * Sends the complete response. {@code body} is {@code null} when there is
     * none; {@code contentEncoding} is non-null when {@code body} is compressed.
     */
    abstract void commit(byte[] body, String contentEncoding);

    /** Sends the response head and returns the sink for a streamed body. */
    abstract OutputStream commitStreaming(String contentEncoding);

    // ---- HttpResponse --------------------------------------------------

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
            if (value.equalsIgnoreCase("close")) {
                closeRequested();
            }
            return this;
        }
        if (name.equalsIgnoreCase("Content-Type")) {
            this.contentType = value;
            return this;
        }
        if (name.equalsIgnoreCase("Content-Encoding")) {
            contentEncodingSet = true; // already encoded by the application: leave the body alone
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
        byte[] bytes = body == null || body.isEmpty() || !hasBody() ? null : body.getBytes(StandardCharsets.UTF_8);
        String encoding = null;
        if (bytes != null && bytes.length >= config.gzipMinBytes()) {
            ContentEncoder encoder = encoder();
            if (encoder != null) {
                bytes = encoder.encode(bytes);
                encoding = encoder.encoding();
            }
        }
        committed = true;
        commit(bytes, encoding);
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
        ContentEncoder encoder = hasBody() && !headRequest ? encoder() : null;
        committed = true;
        OutputStream sink = commitStreaming(encoder == null ? null : encoder.encoding());
        if (headRequest || !hasBody()) {
            stream = OutputStream.nullOutputStream();
        } else {
            stream = encoder == null ? sink : encoder.wrap(sink);
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
            committed = true;
            commit(null, null);
        } else if (stream != null) {
            OutputStream open = stream;
            stream = null;
            try {
                open.close(); // finishes the body if the handler did not
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    // ---- helpers -------------------------------------------------------

    /** 1xx, 204 and 304 responses never carry a body (nor the headers describing one). */
    protected boolean hasBody() {
        return status >= 200 && status != 204 && status != 304;
    }

    protected boolean isHeadRequest() {
        return headRequest;
    }

    /** The codec for this response, when compression is on and both sides allow it. */
    private ContentEncoder encoder() {
        if (!config.gzip() || contentEncodingSet) {
            return null;
        }
        return compression.choose(acceptEncoding(), contentType);
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

    static String reason(int status) {
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

}
