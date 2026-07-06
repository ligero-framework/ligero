package com.ligero.http;

/**
 * Low-level request handler used between the framework pipeline and the
 * server engine. Application code should prefer {@link Handler}.
 */
@FunctionalInterface
public interface HttpHandler {

    void handle(HttpRequest request, HttpResponse response) throws Exception;
}
