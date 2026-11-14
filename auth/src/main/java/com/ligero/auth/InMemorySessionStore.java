package com.ligero.auth;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Default per-process {@link SessionStore}.
 *
 * <p>Sessions expire after an idle timeout (30 minutes by default, sliding on
 * every request) and the store holds at most a fixed number of them, evicting
 * the least recently used when full. Without both limits every cookie-less
 * request would leave a session behind forever — an easy way to exhaust the
 * heap.</p>
 */
public final class InMemorySessionStore implements SessionStore {

    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final int DEFAULT_MAX_SESSIONS = 100_000;

    private record Entry(Session session, AtomicLong lastAccessNanos) {
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final long idleTimeoutNanos;
    private final int maxSessions;
    private final LongSupplier nanoClock;
    private final AtomicLong lastSweepNanos;

    public InMemorySessionStore() {
        this(DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_SESSIONS);
    }

    public InMemorySessionStore(Duration idleTimeout, int maxSessions) {
        this(idleTimeout, maxSessions, System::nanoTime);
    }

    InMemorySessionStore(Duration idleTimeout, int maxSessions, LongSupplier nanoClock) {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.maxSessions = maxSessions;
        this.nanoClock = nanoClock;
        this.lastSweepNanos = new AtomicLong(nanoClock.getAsLong());
    }

    @Override
    public Session create(String id) {
        long now = nanoClock.getAsLong();
        sweepIfDue(now);
        if (sessions.size() >= maxSessions) {
            sweep(now);
            while (sessions.size() >= maxSessions) {
                evictLeastRecentlyUsed();
            }
        }
        Session session = new Session(id);
        sessions.put(id, new Entry(session, new AtomicLong(now)));
        return session;
    }

    @Override
    public Session find(String id) {
        Entry entry = sessions.get(id);
        if (entry == null) {
            return null;
        }
        long now = nanoClock.getAsLong();
        if (now - entry.lastAccessNanos().get() >= idleTimeoutNanos) {
            sessions.remove(id, entry);
            return null;
        }
        entry.lastAccessNanos().set(now); // sliding expiration
        return entry.session();
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }

    /** Number of live (not yet reaped) sessions — diagnostics and tests. */
    public int size() {
        return sessions.size();
    }

    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last >= idleTimeoutNanos / 4 && lastSweepNanos.compareAndSet(last, now)) {
            sweep(now);
        }
    }

    private void sweep(long now) {
        sessions.values().removeIf(entry -> now - entry.lastAccessNanos().get() >= idleTimeoutNanos);
    }

    private void evictLeastRecentlyUsed() {
        sessions.entrySet().stream()
            .min(Comparator.comparingLong(e -> e.getValue().lastAccessNanos().get()))
            .ifPresent(oldest -> sessions.remove(oldest.getKey(), oldest.getValue()));
    }
}
