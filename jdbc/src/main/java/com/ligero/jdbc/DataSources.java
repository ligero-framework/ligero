package com.ligero.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.function.Consumer;

/**
 * Builds pooled {@link DataSource}s with HikariCP — the connection pool almost
 * every JDBC app needs. The returned {@link HikariDataSource} is
 * {@link AutoCloseable}, so a {@code Beans} container closes the pool on
 * shutdown.
 *
 * <pre>{@code
 * DataSource ds = DataSources.pooled("jdbc:postgresql://localhost/app", "app", secret);
 * Jdbc db = new Jdbc(ds);
 * }</pre>
 *
 * <p>For finer control (pool size, timeouts, pool name) pass a customizer:</p>
 *
 * <pre>{@code
 * DataSource ds = DataSources.pooled("jdbc:postgresql://…", "app", secret, cfg -> {
 *     cfg.setMaximumPoolSize(20);
 *     cfg.setPoolName("app-pool");
 * });
 * }</pre>
 */
public final class DataSources {

    private DataSources() {
    }

    /** A pooled DataSource with sensible defaults. */
    public static HikariDataSource pooled(String jdbcUrl, String username, String password) {
        return pooled(jdbcUrl, username, password, config -> { });
    }

    /** A pooled DataSource, letting {@code customizer} tune the {@link HikariConfig}. */
    public static HikariDataSource pooled(String jdbcUrl, String username, String password,
                                          Consumer<HikariConfig> customizer) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("ligero-pool");
        customizer.accept(config);
        return new HikariDataSource(config);
    }
}
