package com.ligero.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCacheTest {

    private final InMemoryCache<String, String> cache = new InMemoryCache<>();

    @Test
    void putGetEvictAndClear() {
        assertThat(cache.get("k")).isEmpty();

        cache.put("k", "v");
        assertThat(cache.get("k")).contains("v");
        assertThat(cache.size()).isEqualTo(1);

        cache.evict("k");
        assertThat(cache.get("k")).isEmpty();

        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertThat(cache.size()).isZero();
    }

    @Test
    void entriesExpireAfterTtl() throws InterruptedException {
        cache.put("k", "v", Duration.ofMillis(30));
        assertThat(cache.get("k")).contains("v");
        Thread.sleep(60);
        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.size()).isZero(); // reaped lazily on the read above
    }

    @Test
    void loadThroughComputesOncePerKey() {
        AtomicInteger loads = new AtomicInteger();
        String first = cache.get("id", k -> "loaded-" + loads.incrementAndGet());
        String second = cache.get("id", k -> "loaded-" + loads.incrementAndGet());
        assertThat(first).isEqualTo("loaded-1");
        assertThat(second).isEqualTo("loaded-1"); // cached, loader not called again
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void loadThroughWithTtlReloadsAfterExpiry() throws InterruptedException {
        AtomicInteger loads = new AtomicInteger();
        cache.get("id", Duration.ofMillis(30), k -> "v" + loads.incrementAndGet());
        Thread.sleep(60);
        String reloaded = cache.get("id", Duration.ofMillis(30), k -> "v" + loads.incrementAndGet());
        assertThat(reloaded).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }
}
