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
        if (!isWellFormed(id)) {
            // absent, or not safe to echo into headers and logs
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

    /**
     * An incoming id is client-controlled and ends up in a response header and
     * in log lines, so only a conservative alphabet and length are accepted.
     */
    static boolean isWellFormed(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Extracts the trace-id field from a {@code version-traceid-spanid-flags} header. */
    static String parseTraceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length == 4 && parts[1].length() == 32 && !parts[1].equals("0".repeat(32))
                && parts[1].chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return parts[1];
        }
        return null;
    }
}
