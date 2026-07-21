package com.ligero.redis;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory {@link RedisOps} for tests — no Redis server needed. */
final class FakeRedisOps implements RedisOps {

    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Map<String, String>> hashes = new LinkedHashMap<>();
    private final Map<String, String> values = new HashMap<>();

    @Override
    public long incrementWithTtl(String key, long ttlSeconds) {
        return counters.merge(key, 1L, Long::sum);
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        Map<String, String> hash = hashes.get(key);
        return hash == null ? Map.of() : new LinkedHashMap<>(hash);
    }

    @Override
    public void hset(String key, Map<String, String> fields, long ttlSeconds) {
        hashes.computeIfAbsent(key, k -> new LinkedHashMap<>()).putAll(fields);
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        values.put(key, value);
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public void delete(String key) {
        counters.remove(key);
        hashes.remove(key);
        values.remove(key);
    }
}
