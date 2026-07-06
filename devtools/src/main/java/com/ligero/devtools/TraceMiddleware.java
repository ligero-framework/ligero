package com.ligero.devtools;

import com.ligero.http.Context;
import com.ligero.http.HttpException;
import com.ligero.middleware.Middleware;

import java.util.UUID;

/**
 * Opens a {@link RequestTrace} for every request (except the devtools
 * endpoints themselves), makes it visible to the spy proxies through
 * {@link DevtoolsRecorder#CURRENT} and publishes the completed trace to the
 * {@link TraceStore} when the pipeline finishes.
 */
final class TraceMiddleware implements Middleware {

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
        String requestId = ctx.attribute("requestId");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        RequestTrace trace = new RequestTrace(requestId, ctx.method(), ctx.path());
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
            store.add(trace);
        }
    }
}
