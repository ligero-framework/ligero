package com.ligero.devtools;

import com.ligero.Ligero;
import com.ligero.http.Context;
import com.ligero.http.HttpException;
import com.ligero.middleware.Middleware;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Opens a {@link RequestTrace} for every request (except the devtools
 * endpoints themselves), makes it visible to the spy proxies through
 * {@link DevtoolsRecorder#CURRENT} and publishes the completed trace to the
 * {@link TraceStore} when the pipeline finishes.
 *
 * <p>When the dashboard fires a request ("try it out") it stamps an
 * {@code X-Ligero-Dev} correlation header; that value becomes the trace id so
 * the browser can match the trace it receives over SSE to the request it sent.</p>
 */
final class TraceMiddleware implements Middleware {

    /** Correlation header the dashboard sets when firing a request. */
    static final String CORRELATION_HEADER = "X-Ligero-Dev";

    private final TraceStore store;

    TraceMiddleware(TraceStore store) {
        this.store = store;
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        if (ctx.path().startsWith(Devtools.BASE_PATH)) {
            chain.proceed();
            return;
        }
        RequestTrace trace = new RequestTrace(traceId(ctx), ctx.method(), ctx.path());
        DevtoolsRecorder.CURRENT.set(trace);
        int errorStatus = 0;
        try {
            chain.proceed();
        } catch (Exception e) {
            // Exception mapping happens outside this middleware, so read the
            // final status from the exception itself before rethrowing.
            errorStatus = e instanceof HttpException http ? http.getStatus() : 500;
            throw e;
        } finally {
            DevtoolsRecorder.CURRENT.remove();
            trace.finish(errorStatus != 0 ? errorStatus : ctx.res().getStatus());
            capture(ctx, trace);
            store.add(trace);
        }
    }

    private static String traceId(Context ctx) {
        String correlation = ctx.header(CORRELATION_HEADER);
        if (correlation != null && !correlation.isBlank()) {
            return correlation;
        }
        String requestId = ctx.attribute("requestId");
        return requestId != null ? requestId : UUID.randomUUID().toString().substring(0, 8);
    }

    /** Records the matched route, the request inputs and the response body. */
    private static void capture(Context ctx, RequestTrace trace) {
        try {
            String route = ctx.attribute(Ligero.MATCHED_ROUTE_ATTRIBUTE);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("pathParams", ctx.pathParams());
            input.put("query", ctx.req().getQueryParams());
            trace.describe(route != null ? route : ctx.path(), JsonValue.of(input));

            Object body = ctx.attribute(Context.RESPONSE_BODY_ATTRIBUTE);
            if (body != null) {
                trace.respondedWith(JsonValue.of(body));
            }
        } catch (RuntimeException ignored) {
            // devtools capture must never interfere with request handling
        }
    }
}
