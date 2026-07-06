package com.ligero.websocket;

/**
 * WebSocket endpoint callbacks. Registered with
 * {@code app.websocket("/chat", handler)}; requires a
 * {@link com.ligero.spi.ServerEngine} with WebSocket support
 * (e.g. {@code ligero-server-jetty}) — the JDK engine rejects WebSocket
 * routes at startup with a clear message.
 */
public interface WsHandler {

    default void onConnect(WsSession session) throws Exception {
    }

    default void onMessage(WsSession session, String message) throws Exception {
    }

    default void onClose(WsSession session, int statusCode, String reason) throws Exception {
    }

    default void onError(WsSession session, Throwable error) {
    }
}
