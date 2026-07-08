/**
 * Ligero Redis: distributed implementations of the rate-limit and session
 * stores, so limits and sessions are shared across app instances.
 */
module com.ligero.redis {
    requires transitive com.ligero.core;
    requires transitive com.ligero.auth;
    // transitive: JedisPool appears in the public usingJedis(...) factories
    requires transitive redis.clients.jedis;
    requires org.apache.commons.pool2;

    exports com.ligero.redis;
}
