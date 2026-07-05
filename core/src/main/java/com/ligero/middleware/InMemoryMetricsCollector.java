package com.ligero.middleware;

import com.ligero.spi.MetricsCollector;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Dependency-free {@link MetricsCollector}: per-route counters and total
 * latency, exposable via {@link #snapshot()} (e.g. from a diagnostics
 * route).
 */
public final class InMemoryMetricsCollector implements MetricsCollector {

    /** Aggregated numbers for one (method, route) pair. */
    public record RouteMetrics(long count, long errors, long totalNanos) {
        public long meanMicros() {
            return count == 0 ? 0 : totalNanos / count / 1_000;
        }
    }

    private record Key(String method, String route) {
    }

    private static final class Cell {
        final LongAdder count = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
    }

    private final Map<Key, Cell> cells = new ConcurrentHashMap<>();

    @Override
    public void record(String method, String route, int status, long durationNanos) {
        Cell cell = cells.computeIfAbsent(new Key(method, route), k -> new Cell());
        cell.count.increment();
        cell.totalNanos.add(durationNanos);
        if (status >= 500) {
            cell.errors.increment();
        }
    }

    /** Immutable snapshot keyed by {@code "METHOD route"}. */
    public Map<String, RouteMetrics> snapshot() {
        Map<String, RouteMetrics> snapshot = new TreeMap<>();
        cells.forEach((key, cell) -> snapshot.put(key.method() + " " + key.route(),
            new RouteMetrics(cell.count.sum(), cell.errors.sum(), cell.totalNanos.sum())));
        return snapshot;
    }
}
