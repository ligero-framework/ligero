package com.ligero.spi;

/**
 * Runtime configuration handed to a {@link ServerEngine}. Derived from
 * {@link com.ligero.config.LigeroConfig} by the framework; engines must not
 * read configuration from anywhere else.
 *
 * @param host           bind address
 * @param port           bind port (0 = ephemeral)
 * @param maxBodyBytes   request body size limit enforced by the engine
 * @param virtualThreads whether to serve requests on virtual threads
 * @param gzip           whether to gzip responses when the client accepts it
 * @param gzipMinBytes   minimum body size before compression kicks in
 * @param bodyMapper     JSON mapper for {@code HttpResponse.json}, may be null
 * @param webSockets     WebSocket routes (path to handler); engines without
 *                       WebSocket support must fail fast when non-empty
 */
public record EngineConfig(
    String host,
    int port,
    long maxBodyBytes,
    boolean virtualThreads,
    boolean gzip,
    int gzipMinBytes,
    BodyMapper bodyMapper,
    java.util.Map<String, com.ligero.websocket.WsHandler> webSockets) {

    public EngineConfig {
        webSockets = webSockets == null ? java.util.Map.of() : java.util.Map.copyOf(webSockets);
    }
}
