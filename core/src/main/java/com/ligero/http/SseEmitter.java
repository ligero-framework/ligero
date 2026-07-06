package com.ligero.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Server-Sent Events emitter. Obtained through {@code ctx.sse()}; the
 * response is committed as {@code text/event-stream} and each
 * {@link #send(String)} writes one event.
 *
 * <pre>{@code
 * app.get("/events", ctx -> {
 *     try (SseEmitter sse = ctx.sse()) {
 *         sse.send("tick");
 *         sse.send("update", "{\"n\":1}");
 *     }
 * });
 * }</pre>
 */
public final class SseEmitter implements AutoCloseable {

    private final OutputStream out;

    SseEmitter(HttpResponse response) {
        response.contentType("text/event-stream; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive");
        this.out = response.getOutputStream();
    }

    /** Sends a {@code data:} event. */
    public SseEmitter send(String data) {
        return write(null, null, data);
    }

    /** Sends a named event. */
    public SseEmitter send(String event, String data) {
        return write(event, null, data);
    }

    /** Sends a named event with an id (enables client resume via Last-Event-ID). */
    public SseEmitter send(String event, String id, String data) {
        return write(event, id, data);
    }

    /** Sends a comment line, useful as keep-alive. */
    public SseEmitter comment(String comment) {
        return raw(": " + comment + "\n\n");
    }

    private SseEmitter write(String event, String id, String data) {
        StringBuilder sb = new StringBuilder();
        if (id != null) {
            sb.append("id: ").append(id).append('\n');
        }
        if (event != null) {
            sb.append("event: ").append(event).append('\n');
        }
        for (String line : String.valueOf(data).split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        return raw(sb.toString());
    }

    private SseEmitter raw(String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("SSE client disconnected", e);
        }
        return this;
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // client already gone
        }
    }
}
