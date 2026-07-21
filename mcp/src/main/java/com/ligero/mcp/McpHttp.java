package com.ligero.mcp;

import com.ligero.http.Context;
import com.ligero.middleware.Middleware;

import java.util.Map;

/**
 * Streamable-HTTP transport for an {@link McpServer}: a single endpoint that
 * accepts a JSON-RPC message by POST and returns the JSON-RPC response as
 * {@code application/json} (or {@code 202 Accepted} for a notification).
 */
final class McpHttp implements Middleware {

    private final String path;
    private final McpServer server;

    McpHttp(String path, McpServer server) {
        this.path = path;
        this.server = server;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(Context ctx, Chain chain) throws Exception {
        if (!path.equals(ctx.path()) || !"POST".equals(ctx.method())) {
            chain.proceed();
            return;
        }
        Map<String, Object> message = ctx.body(Map.class);
        Map<String, Object> response = server.handle(message);
        if (response == null) {
            ctx.status(202).text(""); // accepted notification, no body
        } else {
            ctx.json(response);
        }
    }
}
