package com.ligero.middleware;

import com.ligero.http.Context;

import java.util.UUID;

/**
 * Correlation middleware. Assigns each request an id (incoming
 * {@code X-Request-Id} or a fresh UUID), echoes it in the response, and
 * propagates W3C Trace Context: an incoming {@code traceparent} header is
 * parsed and its trace id exposed as the {@code traceId} attribute so logs
 * and downstream calls can join the distributed trace.
 */
public final class RequestIdMiddleware implements Middleware {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";
    public static final String TRACE_ID_ATTRIBUTE = "traceId";

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String id = ctx.header(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        ctx.attribute(ATTRIBUTE, id);
        ctx.header(HEADER, id);

        String traceparent = ctx.header("traceparent");
        String traceId = parseTraceId(traceparent);
        if (traceId != null) {
            ctx.attribute(TRACE_ID_ATTRIBUTE, traceId);
        }
        chain.proceed();
    }

    /** Extracts the trace-id field from a {@code version-traceid-spanid-flags} header. */
    static String parseTraceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length == 4 && parts[1].length() == 32 && !parts[1].equals("0".repeat(32))) {
            return parts[1];
        }
        return null;
    }
}
