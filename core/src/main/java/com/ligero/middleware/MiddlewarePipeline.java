package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.Handler;

import java.util.List;

/** Composes an ordered middleware list around a terminal handler. */
public final class MiddlewarePipeline {

    private MiddlewarePipeline() {
    }

    /**
     * Builds a handler that runs the middlewares in registration order and
     * finishes with {@code terminal}.
     */
    public static Handler compose(List<Middleware> middlewares, Handler terminal) {
        Handler handler = terminal;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            Middleware middleware = middlewares.get(i);
            Handler next = handler;
            handler = ctx -> middleware.handle(ctx, () -> next.handle(ctx));
        }
        return handler;
    }

    /**
     * Wraps a middleware so it only applies to paths under {@code prefix};
     * other requests skip straight to the next element.
     */
    public static Middleware scoped(String prefix, Middleware middleware) {
        String normalized = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return (Context ctx, Middleware.Chain chain) -> {
            String path = ctx.path();
            if (path.equals(normalized) || path.startsWith(normalized + "/")) {
                middleware.handle(ctx, chain);
            } else {
                chain.proceed();
            }
        };
    }
}
