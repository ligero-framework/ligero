package com.ligero.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCacheTest {

    private final FakeRedisOps ops = new FakeRedisOps();
    private final RedisCache cache = new RedisCache(ops, "cache:");

    @Test
    void putGetAndEvict() {
        assertThat(cache.get("k")).isEmpty();
        cache.put("k", "v");
        assertThat(cache.get("k")).contains("v");
        // stored under the namespaced key
        assertThat(ops.get("cache:k")).isEqualTo("v");
        cache.evict("k");
        assertThat(cache.get("k")).isEmpty();
    }

    @Test
    void loadThroughStoresAndReuses() {
        AtomicInteger loads = new AtomicInteger();
        String first = cache.get("id", Duration.ofMinutes(5), k -> "loaded-" + loads.incrementAndGet());
        String second = cache.get("id", Duration.ofMinutes(5), k -> "loaded-" + loads.incrementAndGet());
        assertThat(first).isEqualTo("loaded-1");
        assertThat(second).isEqualTo("loaded-1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void clearIsUnsupportedOnSharedRedis() {
        assertThatThrownBy(cache::clear).isInstanceOf(UnsupportedOperationException.class);
    }
}
