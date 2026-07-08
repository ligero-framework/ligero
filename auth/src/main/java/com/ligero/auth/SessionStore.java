package com.ligero.auth;

/**
 * SPI for session persistence, so distributed stores can replace the
 * default in-memory map without touching {@link SessionMiddleware}.
 */
public interface SessionStore {

    Session create(String id);

    /** Returns the session or {@code null} when unknown/expired. */
    Session find(String id);

    /**
     * Flushes a session's current state. A no-op for the in-memory store
     * (the returned object is the stored one); distributed stores (Redis)
     * override it to write attribute changes back after each request.
     */
    default void save(Session session) {
    }

    void delete(String id);
}
