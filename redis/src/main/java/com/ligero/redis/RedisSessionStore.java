package com.ligero.redis;

import com.ligero.auth.Session;
import com.ligero.auth.SessionStore;

import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Distributed {@link SessionStore}: each session is a Redis hash
 * ({@code session:<id>}) with a sliding TTL, so sessions survive across app
 * instances and restarts. {@code SessionMiddleware} calls {@link #save} after
 * each request to flush attribute changes.
 *
 * <p>Attribute values are stored as strings ({@code String.valueOf(value)}) —
 * sessions typically hold small string data (a user id, roles). Keep it that
 * way; put large or structured state in your database, not the session.</p>
 *
 * <pre>{@code
 * SessionStore sessions = RedisSessionStore.usingJedis(pool, Duration.ofHours(1));
 * app.use(SessionMiddleware.of(secret, sessions));
 * }</pre>
 */
public final class RedisSessionStore implements SessionStore {

    private static final String MARKER = "__ligero";

    private final RedisOps ops;
    private final long ttlSeconds;

    public RedisSessionStore(RedisOps ops, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ops = ops;
        this.ttlSeconds = ttl.toSeconds();
    }

    /** Convenience factory over a Jedis pool. */
    public static RedisSessionStore usingJedis(JedisPool pool, Duration ttl) {
        return new RedisSessionStore(new JedisRedisOps(pool), ttl);
    }

    @Override
    public Session create(String id) {
        // a marker field makes the hash exist even before any attribute is set
        ops.hset(key(id), Map.of(MARKER, "1"), ttlSeconds);
        return new Session(id);
    }

    @Override
    public Session find(String id) {
        Map<String, String> data = ops.hgetAll(key(id));
        if (data.isEmpty()) {
            return null;
        }
        Session session = new Session(id);
        data.forEach((k, v) -> {
            if (!MARKER.equals(k)) {
                session.set(k, v);
            }
        });
        return session;
    }

    @Override
    public void save(Session session) {
        Map<String, String> fields = new HashMap<>();
        fields.put(MARKER, "1");
        session.attributes().forEach((k, v) -> fields.put(k, String.valueOf(v)));
        ops.hset(key(session.id()), fields, ttlSeconds); // also refreshes the TTL (sliding)
    }

    @Override
    public void delete(String id) {
        ops.delete(key(id));
    }

    private static String key(String id) {
        return "session:" + id;
    }
}
