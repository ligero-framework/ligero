package com.ligero.websocket;

import java.util.Map;

/** One connected WebSocket peer. Implementations are engine adapters. */
public interface WsSession {

    /** Unique id of this connection. */
    String id();

    /** Path the socket was registered under (e.g. {@code /chat}). */
    String path();

    /** Sends a text frame. */
    void send(String message);

    /** Closes with the given status code (RFC 6455) and reason. */
    void close(int statusCode, String reason);

    default void close() {
        close(1000, "normal closure");
    }

    /** Mutable per-connection attributes (e.g. the authenticated user). */
    Map<String, Object> attributes();
}
