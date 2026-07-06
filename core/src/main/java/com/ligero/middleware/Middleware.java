package com.ligero.middleware;

import com.ligero.http.Context;

/**
 * Composable request/response interceptor (OCP: every cross-cutting concern
 * — CORS, auth, logging, static files… — is a middleware, never a core
 * change).
 *
 * <pre>{@code
 * app.use((ctx, chain) -> {
 *     long start = System.nanoTime();
 *     chain.proceed();
 *     log.info("{} took {} µs", ctx.path(), (System.nanoTime() - start) / 1000);
 * });
 * }</pre>
 *
 * <p>Not calling {@link Chain#proceed()} short-circuits the pipeline; the
 * middleware is then responsible for producing a response.</p>
 */
@FunctionalInterface
public interface Middleware {

    void handle(Context ctx, Chain chain) throws Exception;

    /** Continuation of the pipeline. */
    @FunctionalInterface
    interface Chain {
        void proceed() throws Exception;
    }
}
