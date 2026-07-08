package com.ligero.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;

/**
 * Runs Flyway database migrations — one call, at startup, before you serve
 * traffic. Migration scripts live on the classpath under
 * {@code db/migration} by default ({@code V1__init.sql}, {@code V2__...sql}).
 *
 * <pre>{@code
 * DataSource ds = ...;
 * Migrations.run(ds);          // apply everything under classpath:db/migration
 *
 * Ligero app = Ligero.create();
 * app.beans(beans);
 * app.start();
 * }</pre>
 *
 * <p>Optional and unopinionated: if you prefer Liquibase, or running Flyway
 * from your build/CI instead of the app, ignore this module. It just removes
 * the boilerplate for the common "migrate on boot" case.</p>
 */
public final class Migrations {

    private Migrations() {
    }

    /** Applies pending migrations from {@code classpath:db/migration}. */
    public static int run(DataSource dataSource) {
        return run(dataSource, "classpath:db/migration");
    }

    /** Applies pending migrations from the given locations, returning how many were applied. */
    public static int run(DataSource dataSource, String... locations) {
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(locations)
            .load();
        MigrateResult result = flyway.migrate();
        return result.migrationsExecuted;
    }
}
