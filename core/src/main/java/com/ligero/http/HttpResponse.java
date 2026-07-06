package com.ligero.http;

import java.io.OutputStream;

/**
 * Represents an HTTP response. Implementations are provided by a
 * {@link com.ligero.spi.ServerEngine} adapter.
 */
public interface HttpResponse {

    /** Sets the status code. Must be called before the response is committed. */
    HttpResponse status(int statusCode);

    /** Current status code (200 unless changed). */
    int getStatus();

    /** Sets a response header. */
    HttpResponse header(String name, String value);

    /** Sets the {@code Content-Type} header value used when the body is sent. */
    HttpResponse contentType(String contentType);

    /** Sends the given body and commits the response. */
    HttpResponse send(String body);

    /**
     * Serializes the object as JSON and sends it with
     * {@code application/json}. Requires a {@link com.ligero.spi.BodyMapper}
     * implementation on the classpath (e.g. {@code ligero-json}).
     */
    HttpResponse json(Object object);

    /**
     * Commits headers and returns the raw output stream for streaming
     * responses.
     */
    OutputStream getOutputStream();

    /** Redirects with status 302. */
    default HttpResponse redirect(String url) {
        return redirect(url, 302);
    }

    /** Redirects with the given 3xx status (301, 302, 303, 307, 308). */
    HttpResponse redirect(String url, int statusCode);

    /** True once the status line and headers have been written. */
    boolean isCommitted();

    /** Commits the response without a body if nothing has been sent yet. */
    void end();
}
