package com.ligero.middleware;

import com.ligero.http.Context;

import java.util.UUID;

/**
 * Assigns each request an identifier: reuses the incoming
 * {@code X-Request-Id} header when present, otherwise generates a UUID.
 * The id is exposed as the {@code requestId} context attribute and echoed
 * in the response header.
 */
public final class RequestIdMiddleware implements Middleware {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String id = ctx.header(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        ctx.attribute(ATTRIBUTE, id);
        ctx.header(HEADER, id);
        chain.proceed();
    }
}
