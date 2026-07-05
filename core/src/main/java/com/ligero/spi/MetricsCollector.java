package com.ligero.spi;

/**
 * SPI for metrics backends. The framework reports one observation per
 * request through {@code MetricsMiddleware}; adapters (e.g.
 * {@code ligero-metrics-micrometer}) forward them to a real registry.
 */
public interface MetricsCollector {

    /**
     * Records one served request.
     *
     * @param method        HTTP method
     * @param route         matched route pattern (e.g. {@code /users/{id}}),
     *                      or the raw path when no route matched
     * @param status        response status code
     * @param durationNanos wall time spent serving the request
     */
    void record(String method, String route, int status, long durationNanos);
}
