package com.ligero.jdbc;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourcesTest {

    private static final RowMapper<Integer> AS_ONE = r -> r.getInt(1);

    @Test
    void buildsAPooledDataSourceUsableByJdbc() {
        String url = "jdbc:h2:mem:pool" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (HikariDataSource ds = DataSources.pooled(url, "sa", "", cfg -> {
            cfg.setMaximumPoolSize(3);
            cfg.setPoolName("test-pool");
        })) {
            assertThat(ds.getMaximumPoolSize()).isEqualTo(3);
            assertThat(ds.getPoolName()).isEqualTo("test-pool");

            Jdbc db = new Jdbc(ds);
            List<Integer> rows = db.query("select 1", AS_ONE);
            assertThat(rows).containsExactly(1);
        }
    }

    @Test
    void defaultPoolHasLigeroPoolName() {
        String url = "jdbc:h2:mem:pool" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (HikariDataSource ds = DataSources.pooled(url, "sa", "")) {
            assertThat(ds.getPoolName()).isEqualTo("ligero-pool");
        }
    }
}
