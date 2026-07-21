/**
 * Ligero JDBC: a tiny, explicit helper over a {@code DataSource} — you write
 * the SQL, it maps rows to your records and handles transactions. No ORM, no
 * reflection.
 */
module com.ligero.jdbc {
    requires transitive com.ligero.core;
    requires transitive java.sql;
    // JedisPool/HikariDataSource appear on the public DataSources factory.
    requires transitive com.zaxxer.hikari;

    exports com.ligero.jdbc;
}
