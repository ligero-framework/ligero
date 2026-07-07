package com.ligero.redis;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory {@link RedisOps} for tests — no Redis server needed. */
final class FakeRedisOps implements RedisOps {

    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Map<String, String>> hashes = new LinkedHashMap<>();

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
    public void delete(String key) {
        counters.remove(key);
        hashes.remove(key);
    }
}
