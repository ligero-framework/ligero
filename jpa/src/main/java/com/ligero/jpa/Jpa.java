package com.ligero.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A thin, explicit helper over a JPA {@link EntityManagerFactory}. It owns the
 * factory (a singleton bean) and hands out short-lived {@link EntityManager}s
 * per unit of work — no open-session-in-view, no thread-locals, no proxies you
 * didn't ask for.
 *
 * <pre>{@code
 * // wire it as a bean (one factory for the app)
 * Jpa jpa = Jpa.forUnit("app");                 // reads META-INF/persistence.xml
 *
 * // write in a transaction
 * Todo saved = jpa.tx(em -> { em.persist(todo); return todo; });
 *
 * // read without a transaction
 * List<Todo> all = jpa.read(em -> em.createQuery("select t from Todo t", Todo.class).getResultList());
 *
 * jpa.close();   // closes the factory on shutdown (AutoCloseable)
 * }</pre>
 *
 * <p>This is the "Ligero way": a small layer that keeps you in control of the
 * SQL/JPQL and the transaction boundaries. If you'd rather use jOOQ, Spring
 * Data or raw JDBC, bind that as a bean instead — nothing here is mandatory.</p>
 */
public final class Jpa implements AutoCloseable {

    private final EntityManagerFactory factory;

    private Jpa(EntityManagerFactory factory) {
        this.factory = factory;
    }

    /** Wraps an already-built factory (e.g. one you configured programmatically). */
    public static Jpa of(EntityManagerFactory factory) {
        return new Jpa(factory);
    }

    /** Boots the persistence unit named in {@code META-INF/persistence.xml}. */
    public static Jpa forUnit(String persistenceUnit) {
        return new Jpa(Persistence.createEntityManagerFactory(persistenceUnit));
    }

    /** Boots the unit, overriding/adding properties (e.g. the JDBC URL from config). */
    public static Jpa forUnit(String persistenceUnit, Map<String, Object> properties) {
        return new Jpa(Persistence.createEntityManagerFactory(persistenceUnit, properties));
    }

    /** The underlying factory, for advanced use. */
    public EntityManagerFactory factory() {
        return factory;
    }

    /** Runs {@code work} in a transaction, committing on success and rolling back on error. */
    public <T> T tx(Function<EntityManager, T> work) {
        EntityManager em = factory.createEntityManager();
        EntityTransaction transaction = em.getTransaction();
        try {
            transaction.begin();
            T result = work.apply(em);
            transaction.commit();
            return result;
        } catch (RuntimeException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /** Transactional work with no return value. */
    public void tx(Consumer<EntityManager> work) {
        tx(em -> {
            work.accept(em);
            return null;
        });
    }

    /** Runs {@code work} with a short-lived entity manager, no transaction (reads). */
    public <T> T read(Function<EntityManager, T> work) {
        EntityManager em = factory.createEntityManager();
        try {
            return work.apply(em);
        } finally {
            em.close();
        }
    }

    @Override
    public void close() {
        factory.close();
    }
}
