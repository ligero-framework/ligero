package com.ligero.websocket;

/**
 * WebSocket endpoint callbacks. Registered with
 * {@code app.websocket("/chat", handler)}; requires a
 * {@link com.ligero.spi.ServerEngine} with WebSocket support
 * (e.g. {@code ligero-server-jetty}) — the JDK engine rejects WebSocket
 * routes at startup with a clear message.
 */
public interface WsHandler {

    /**
     * Decides whether a handshake from the given browser {@code Origin} is
     * accepted; rejected handshakes get a {@code 403}. The default allows
     * non-browser clients (no {@code Origin} header) and same-origin pages
     * (the origin's host and port equal the request's {@code Host}).
     *
     * <p>Browsers attach cookies to WebSocket handshakes regardless of which
     * site opens them and do not apply CORS, so without this check any web
     * page could open an authenticated socket on a visitor's behalf
     * (cross-site WebSocket hijacking). Override it to allow additional,
     * explicitly trusted origins.</p>
     *
     * @param origin value of the {@code Origin} header, or {@code null}
     * @param host   value of the {@code Host} header, or {@code null}
     */
    default boolean acceptOrigin(String origin, String host) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        int scheme = origin.indexOf("://");
        String authority = scheme < 0 ? origin : origin.substring(scheme + 3);
        boolean tls = origin.regionMatches(true, 0, "https://", 0, 8);
        return stripDefaultPort(authority, tls).equalsIgnoreCase(stripDefaultPort(host, tls));
    }

    private static String stripDefaultPort(String authority, boolean tls) {
        String suffix = tls ? ":443" : ":80";
        return authority.endsWith(suffix)
            ? authority.substring(0, authority.length() - suffix.length()) : authority;
    }

    default void onConnect(WsSession session) throws Exception {
    }

    default void onMessage(WsSession session, String message) throws Exception {
    }

    default void onClose(WsSession session, int statusCode, String reason) throws Exception {
    }

    default void onError(WsSession session, Throwable error) {
    }
}
