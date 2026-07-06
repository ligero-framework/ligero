package com.ligero.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Default per-process {@link SessionStore}. */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session create(String id) {
        Session session = new Session(id);
        sessions.put(id, session);
        return session;
    }

    @Override
    public Session find(String id) {
        return sessions.get(id);
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }
}
