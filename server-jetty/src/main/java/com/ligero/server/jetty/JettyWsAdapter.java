package com.ligero.server.jetty;

import com.ligero.websocket.WsHandler;
import com.ligero.websocket.WsSession;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Jetty's WebSocket listener API to the Ligero {@link WsHandler} SPI.
 * Public because Jetty introspects the listener methods via MethodHandles.
 */
public final class JettyWsAdapter implements Session.Listener.AutoDemanding {

    private static final Logger log = LoggerFactory.getLogger(JettyWsAdapter.class);

    private final String path;
    private final WsHandler handler;
    private JettySession wsSession;

    JettyWsAdapter(String path, WsHandler handler) {
        this.path = path;
        this.handler = handler;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        wsSession = new JettySession(path, session);
        try {
            handler.onConnect(wsSession);
        } catch (Exception e) {
            handler.onError(wsSession, e);
            session.close(1011, "connect handler failed", Callback.NOOP);
        }
    }

    @Override
    public void onWebSocketText(String message) {
        try {
            handler.onMessage(wsSession, message);
        } catch (Exception e) {
            handler.onError(wsSession, e);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        try {
            handler.onClose(wsSession, statusCode, reason);
        } catch (Exception e) {
            log.warn("WebSocket close handler failed for {}", path, e);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        handler.onError(wsSession, cause);
    }

    private static final class JettySession implements WsSession {

        private final String id = UUID.randomUUID().toString();
        private final String path;
        private final Session session;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        JettySession(String path, Session session) {
            this.path = path;
            this.session = session;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public void send(String message) {
            session.sendText(message, Callback.NOOP);
        }

        @Override
        public void close(int statusCode, String reason) {
            session.close(statusCode, reason, Callback.NOOP);
        }

        @Override
        public Map<String, Object> attributes() {
            return attributes;
        }
    }
}
