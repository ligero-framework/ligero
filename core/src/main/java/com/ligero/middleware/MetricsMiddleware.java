package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.spi.MetricsCollector;

/**
 * Records one observation per request (method, matched route, status,
 * duration) through the {@link MetricsCollector} SPI. Use
 * {@link InMemoryMetricsCollector} for a dependency-free default or the
 * {@code ligero-metrics-micrometer} adapter for real registries.
 */
public final class MetricsMiddleware implements Middleware {

    /** Context attribute set by the router with the matched route pattern. */
    public static final String ROUTE_ATTRIBUTE = "ligero.route";

    /**
     * Route reported for requests that matched no route (404s, scans). Using
     * the raw path instead would let anyone create a new metric series per
     * URL they make up, until the metrics backend runs out of memory.
     */
    public static final String UNMATCHED_ROUTE = "UNMATCHED";

    private final MetricsCollector collector;

    public MetricsMiddleware(MetricsCollector collector) {
        this.collector = collector;
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        long start = System.nanoTime();
        try {
            chain.proceed();
        } finally {
            String route = ctx.attribute(ROUTE_ATTRIBUTE);
            collector.record(ctx.method(), route != null ? route : UNMATCHED_ROUTE,
                ctx.res().getStatus(), System.nanoTime() - start);
        }
    }
}
