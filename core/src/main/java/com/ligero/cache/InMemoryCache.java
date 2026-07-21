package com.ligero.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-process {@link Cache} backed by a {@link ConcurrentHashMap}, with lazy
 * per-entry TTL expiry (expired entries are dropped on access). No background
 * thread; pair it with a {@code Scheduler} task if you want periodic cleanup of
 * entries that are never read again.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class InMemoryCache<K, V> implements Cache<K, V> {

    private record Entry<V>(V value, long expiresAtNanos) {
        boolean isLive(long now) {
            return expiresAtNanos == 0L || now < expiresAtNanos;
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();

    private static long deadline(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return 0L; // no expiry
        }
        return System.nanoTime() + ttl.toNanos();
    }

    @Override
    public Optional<V> get(K key) {
        long now = System.nanoTime();
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.isLive(now)) {
            store.remove(key, entry); // drop only if unchanged
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value());
    }

    @Override
    public void put(K key, V value) {
        store.put(key, new Entry<>(value, 0L));
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        store.put(key, new Entry<>(value, deadline(ttl)));
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        return get(key, null, loader);
    }

    @Override
    public V get(K key, Duration ttl, Function<? super K, ? extends V> loader) {
        long now = System.nanoTime();
        Entry<V> current = store.get(key);
        if (current != null && current.isLive(now)) {
            return current.value();
        }
        // compute() so concurrent callers for the same key load at most once
        Entry<V> computed = store.compute(key, (k, existing) -> {
            if (existing != null && existing.isLive(System.nanoTime())) {
                return existing;
            }
            return new Entry<>(loader.apply(k), deadline(ttl));
        });
        return computed.value();
    }

    @Override
    public void evict(K key) {
        store.remove(key);
    }

    @Override
    public void clear() {
        store.clear();
    }

    /** Current number of entries (including any not-yet-reaped expired ones). */
    public int size() {
        return store.size();
    }
}
