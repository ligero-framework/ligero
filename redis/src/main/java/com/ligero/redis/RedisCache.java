package com.ligero.redis;

import com.ligero.cache.Cache;

import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Distributed {@link Cache} of strings backed by Redis, so a cache is shared
 * across every app instance. Keys are namespaced under a prefix
 * ({@code cache:} by default); values are stored as-is — serialize structured
 * data (e.g. to JSON) before caching it.
 *
 * <pre>{@code
 * Cache<String, String> cache = RedisCache.usingJedis(pool);
 * String user = cache.get(id, Duration.ofMinutes(10), this::loadUserJson);
 * }</pre>
 */
public final class RedisCache implements Cache<String, String> {

    private final RedisOps ops;
    private final String prefix;

    public RedisCache(RedisOps ops, String prefix) {
        this.ops = ops;
        this.prefix = prefix;
    }

    /** Convenience factory over a Jedis pool, using the {@code cache:} prefix. */
    public static RedisCache usingJedis(JedisPool pool) {
        return new RedisCache(new JedisRedisOps(pool), "cache:");
    }

    private String key(String key) {
        return prefix + key;
    }

    private static long ttlSeconds(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? 0L : ttl.toSeconds();
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(ops.get(key(key)));
    }

    @Override
    public void put(String key, String value) {
        ops.set(key(key), value, 0L);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        ops.set(key(key), value, ttlSeconds(ttl));
    }

    @Override
    public String get(String key, Function<? super String, ? extends String> loader) {
        return get(key, null, loader);
    }

    @Override
    public String get(String key, Duration ttl, Function<? super String, ? extends String> loader) {
        String existing = ops.get(key(key));
        if (existing != null) {
            return existing;
        }
        String loaded = loader.apply(key);
        if (loaded != null) {
            ops.set(key(key), loaded, ttlSeconds(ttl));
        }
        return loaded;
    }

    @Override
    public void evict(String key) {
        ops.delete(key(key));
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(
            "RedisCache.clear() is intentionally unsupported — scanning/flushing a shared "
                + "Redis is unsafe; evict specific keys or use a dedicated Redis database.");
    }
}
