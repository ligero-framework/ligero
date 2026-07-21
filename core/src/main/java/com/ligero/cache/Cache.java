package com.ligero.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * A minimal cache abstraction: get, put (optionally with a TTL) and evict, plus
 * a load-through helper. Implementations are thread-safe.
 *
 * <p>The in-process default is {@link InMemoryCache}; {@code ligero-redis}
 * provides a distributed {@code RedisCache} for sharing a cache across
 * instances. Program against this interface so you can swap between them.</p>
 *
 * <pre>{@code
 * Cache<String, User> users = new InMemoryCache<>();
 * User u = users.get(id, Duration.ofMinutes(10), this::loadUser); // load-through + TTL
 * users.evict(id);
 * }</pre>
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface Cache<K, V> {

    /** The cached value, or empty when absent or expired. */
    Optional<V> get(K key);

    /** Stores a value that never expires (until evicted or overwritten). */
    void put(K key, V value);

    /** Stores a value that expires after {@code ttl}. */
    void put(K key, V value, Duration ttl);

    /** Returns the cached value, computing and caching it (no expiry) if absent. */
    V get(K key, Function<? super K, ? extends V> loader);

    /** Returns the cached value, computing and caching it with {@code ttl} if absent. */
    V get(K key, Duration ttl, Function<? super K, ? extends V> loader);

    /** Removes the entry for {@code key}, if any. */
    void evict(K key);

    /** Removes every entry. */
    void clear();
}
