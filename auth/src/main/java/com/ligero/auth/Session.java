package com.ligero.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side session: a mutable attribute map bound to a session id. */
public final class Session {

    private final String id;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** Public so out-of-package {@link SessionStore}s (e.g. Redis) can rebuild sessions. */
    public Session(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public void set(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public void remove(String key) {
        attributes.remove(key);
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
