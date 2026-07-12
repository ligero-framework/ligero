package com.ligero.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Error paths (SQL failures wrapped in {@link JdbcException}) and
 * transaction semantics (commit on success, rollback on exception).
 */
class JdbcErrorAndTxTest {

    private static final RowMapper<Long> AS_ID = r -> r.getLong("id");

    private Jdbc db;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jdbcerr" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        db = new Jdbc(ds);
        db.update("create table t(id bigint auto_increment primary key, name varchar(50))");
    }

    @Test
    void queryFailureIsWrapped() {
        assertThatThrownBy(() -> db.query("select * from does_not_exist", AS_ID))
            .isInstanceOf(JdbcException.class)
            .hasMessageContaining("does_not_exist");
    }

    @Test
    void updateFailureIsWrapped() {
        assertThatThrownBy(() -> db.update("update nope set x = 1"))
            .isInstanceOf(JdbcException.class);
    }

    @Test
    void insertFailureIsWrapped() {
        assertThatThrownBy(() -> db.insert("insert into nope(x) values (1)"))
            .isInstanceOf(JdbcException.class);
    }

    @Test
    void transactionCommitsOnSuccess() {
        long id = db.tx(tx -> tx.insert("insert into t(name) values (?)", "committed"));
        assertThat(id).isPositive();
        assertThat(db.query("select id from t", AS_ID)).hasSize(1);
    }

    @Test
    void transactionRollsBackOnException() {
        assertThatThrownBy(() -> db.tx(tx -> {
            tx.insert("insert into t(name) values (?)", "doomed");
            throw new IllegalStateException("fail after insert");
        })).isInstanceOf(IllegalStateException.class);

        // the insert must have been rolled back
        assertThat(db.query("select id from t", AS_ID)).isEmpty();
    }
}
