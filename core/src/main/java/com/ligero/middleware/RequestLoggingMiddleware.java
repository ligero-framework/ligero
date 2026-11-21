package com.ligero.middleware;

import com.ligero.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access log middleware: one line per request with method, path, status
 * and duration. {@link #json()} switches to a structured JSON line
 * (machine-parseable access log) that also carries the request id when the
 * {@link RequestIdMiddleware} runs earlier in the pipeline.
 */
public final class RequestLoggingMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger("ligero.access");

    private final boolean jsonFormat;

    public RequestLoggingMiddleware() {
        this(false);
    }

    private RequestLoggingMiddleware(boolean jsonFormat) {
        this.jsonFormat = jsonFormat;
    }

    /** Structured JSON access log. */
    public static RequestLoggingMiddleware json() {
        return new RequestLoggingMiddleware(true);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        long start = System.nanoTime();
        try {
            chain.proceed();
        } finally {
            long micros = (System.nanoTime() - start) / 1_000;
            if (jsonFormat) {
                String requestId = ctx.attribute(RequestIdMiddleware.ATTRIBUTE);
                log.info("{\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"durationMicros\":{}{}}",
                    escape(ctx.method()), escape(ctx.path()), ctx.res().getStatus(), micros,
                    requestId == null ? "" : ",\"requestId\":\"" + escape(requestId) + "\"");
            } else {
                log.info("{} {} -> {} ({} µs)", ctx.method(), ctx.path(), ctx.res().getStatus(), micros);
            }
        }
    }

    /** JSON string escaping, so a crafted path cannot forge or break log records. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
