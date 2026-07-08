package com.ligero.migrations;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationsTest {

    private static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    @Test
    void appliesAllMigrationsThenIsIdempotent() throws Exception {
        JdbcDataSource ds = h2();

        int applied = Migrations.run(ds);
        assertThat(applied).isEqualTo(2); // V1 + V2

        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from widgets where active = true")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2); // rows from V1, column from V2
        }

        // running again applies nothing new
        assertThat(Migrations.run(ds)).isZero();
    }
}
