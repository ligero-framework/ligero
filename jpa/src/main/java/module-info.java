/**
 * Ligero JPA: a thin, explicit helper over a JPA {@code EntityManagerFactory}
 * — transactions and short-lived entity managers, no session-in-view magic.
 * Bring your own provider (Hibernate, EclipseLink, ...).
 */
module com.ligero.jpa {
    requires transitive com.ligero.core;
    requires transitive jakarta.persistence;

    exports com.ligero.jpa;
}
