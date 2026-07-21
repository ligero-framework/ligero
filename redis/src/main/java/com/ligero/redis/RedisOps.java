package com.ligero.redis;

import java.util.Map;

/**
 * The small set of Redis operations the stores need. A thin seam so the store
 * logic is testable without a live Redis, and so a different client could be
 * substituted for {@link JedisRedisOps}.
 */
public interface RedisOps {

    /** {@code INCR key}, setting {@code EXPIRE} when the counter is first created. */
    long incrementWithTtl(String key, long ttlSeconds);

    /** {@code HGETALL key} (empty map when the key is absent). */
    Map<String, String> hgetAll(String key);

    /** {@code HSET key fields} then {@code EXPIRE key ttlSeconds}. */
    void hset(String key, Map<String, String> fields, long ttlSeconds);

    /** {@code SET key value}, adding {@code EX ttlSeconds} when {@code ttlSeconds > 0}. */
    void set(String key, String value, long ttlSeconds);

    /** {@code GET key} ({@code null} when the key is absent). */
    String get(String key);

    /** {@code DEL key}. */
    void delete(String key);
}
