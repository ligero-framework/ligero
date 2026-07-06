package com.ligero.auth;

/**
 * SPI for session persistence, so distributed stores can replace the
 * default in-memory map without touching {@link SessionMiddleware}.
 */
public interface SessionStore {

    Session create(String id);

    /** Returns the session or {@code null} when unknown/expired. */
    Session find(String id);

    void delete(String id);
}
