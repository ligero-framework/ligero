package com.ligero.redis;

import com.ligero.auth.Session;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSessionStoreTest {

    private final RedisSessionStore store = new RedisSessionStore(new FakeRedisOps(), Duration.ofHours(1));

    @Test
    void createFindPersistsAcrossLookups() {
        Session created = store.create("abc");
        created.set("userId", "42");
        store.save(created);

        Session found = store.find("abc"); // a fresh instance, as a distributed store returns
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("abc");
        assertThat((String) found.get("userId")).isEqualTo("42");
    }

    @Test
    void findReturnsNullForUnknownSession() {
        assertThat(store.find("missing")).isNull();
    }

    @Test
    void newlyCreatedSessionExistsEvenWithNoAttributes() {
        store.create("empty");
        Session found = store.find("empty");
        assertThat(found).isNotNull();
        assertThat(found.attributes()).isEmpty(); // the internal marker is not exposed
    }

    @Test
    void deleteRemovesTheSession() {
        store.create("gone");
        store.delete("gone");
        assertThat(store.find("gone")).isNull();
    }
}
