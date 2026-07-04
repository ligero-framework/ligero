package com.ligero.middleware;

import com.ligero.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs one line per request: method, path, status and duration. */
public final class RequestLoggingMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger("ligero.access");

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        long start = System.nanoTime();
        try {
            chain.proceed();
        } finally {
            long micros = (System.nanoTime() - start) / 1_000;
            log.info("{} {} -> {} ({} µs)", ctx.method(), ctx.path(), ctx.res().getStatus(), micros);
        }
    }
}
