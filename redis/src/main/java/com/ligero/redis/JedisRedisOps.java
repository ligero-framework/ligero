package com.ligero.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;

/** {@link RedisOps} over a Jedis connection pool. */
public final class JedisRedisOps implements RedisOps {

    private final JedisPool pool;

    public JedisRedisOps(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public long incrementWithTtl(String key, long ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            long value = jedis.incr(key);
            if (value == 1L) {
                jedis.expire(key, ttlSeconds);
            }
            return value;
        }
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(key);
        }
    }

    @Override
    public void hset(String key, Map<String, String> fields, long ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            if (!fields.isEmpty()) {
                jedis.hset(key, fields);
            }
            jedis.expire(key, ttlSeconds);
        }
    }

    @Override
    public void delete(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
    }
}
