/**
 * Ligero migrations: a one-call wrapper to run Flyway migrations against a
 * {@code DataSource} at startup.
 */
module com.ligero.migrations {
    requires transitive com.ligero.core;
    requires transitive java.sql;
    requires org.flywaydb.core;

    exports com.ligero.migrations;
}
