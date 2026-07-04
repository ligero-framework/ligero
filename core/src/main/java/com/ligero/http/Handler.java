package com.ligero.http;

/**
 * Application-level request handler receiving a {@link Context}.
 * Any thrown exception is routed through the registered exception handlers.
 */
@FunctionalInterface
public interface Handler {

    void handle(Context ctx) throws Exception;
}
