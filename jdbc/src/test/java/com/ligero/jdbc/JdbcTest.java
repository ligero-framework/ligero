package com.ligero.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTest {

    record Product(long id, String name) {
    }

    private static final RowMapper<Product> AS_PRODUCT =
        r -> new Product(r.getLong("id"), r.getString("name"));

    private Jdbc db;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jdbc" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        db = new Jdbc(ds);
        db.update("create table products(id bigint auto_increment primary key, name varchar(100))");
    }

    @Test
    void insertReturnsGeneratedKeyAndQueryMapsRows() {
        long id = db.insert("insert into products(name) values (?)", "Keyboard");
        assertThat(id).isPositive();

        Optional<Product> one = db.queryOne("select id, name from products where id = ?", AS_PRODUCT, id);
        assertThat(one).contains(new Product(id, "Keyboard"));

        db.insert("insert into products(name) values (?)", "Mouse");
        List<Product> all = db.query("select id, name from products order by id", AS_PRODUCT);
        assertThat(all).extracting(Product::name).containsExactly("Keyboard", "Mouse");
    }

    @Test
    void queryOneIsEmptyWhenNoMatch() {
        assertThat(db.queryOne("select id, name from products where id = ?", AS_PRODUCT, 999)).isEmpty();
    }

    @Test
    void updateReturnsAffectedRows() {
        long id = db.insert("insert into products(name) values (?)", "Old");
        int affected = db.update("update products set name = ? where id = ?", "New", id);
        assertThat(affected).isEqualTo(1);
        assertThat(db.queryOne("select id, name from products where id = ?", AS_PRODUCT, id))
            .map(Product::name).contains("New");
    }

    @Test
    void transactionCommitsOnSuccess() {
        long id = db.tx(tx -> {
            long pid = tx.insert("insert into products(name) values (?)", "Bundle");
            tx.update("update products set name = ? where id = ?", "Bundle+", pid);
            return pid;
        });
        assertThat(db.queryOne("select id, name from products where id = ?", AS_PRODUCT, id))
            .map(Product::name).contains("Bundle+");
    }

    @Test
    void transactionRollsBackOnError() {
        assertThatThrownBy(() -> db.tx(tx -> {
            tx.insert("insert into products(name) values (?)", "doomed");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(db.query("select id, name from products", AS_PRODUCT)).isEmpty();
    }

    @Test
    void badSqlThrowsJdbcExceptionWithTheStatement() {
        assertThatThrownBy(() -> db.query("select nope from missing", AS_PRODUCT))
            .isInstanceOf(JdbcException.class)
            .hasMessageContaining("select nope from missing");
    }

    @Test
    void badUpdateAndInsertAlsoThrowJdbcException() {
        assertThatThrownBy(() -> db.update("update missing set x = 1"))
            .isInstanceOf(JdbcException.class)
            .hasMessageContaining("update missing set x = 1");
        assertThatThrownBy(() -> db.insert("insert into missing(x) values (1)"))
            .isInstanceOf(JdbcException.class)
            .hasMessageContaining("insert into missing");
    }

    @Test
    void insertWithoutGeneratedKeyReturnsMinusOne() {
        // An UPDATE routed through insert() produces no generated keys.
        long none = db.insert("update products set name = ? where id = ?", "nobody", 999);
        assertThat(none).isEqualTo(-1L);
    }

    @Test
    void transactionalReadsSeeUncommittedWritesWithinTheSameTx() {
        List<Product> seen = db.tx(tx -> {
            long pid = tx.insert("insert into products(name) values (?)", "InTx");
            // read back through the transactional handle (tx.query / tx.queryOne)
            assertThat(tx.queryOne("select id, name from products where id = ?", AS_PRODUCT, pid))
                .map(Product::name).contains("InTx");
            return tx.query("select id, name from products order by id", AS_PRODUCT);
        });
        assertThat(seen).extracting(Product::name).containsExactly("InTx");
    }
}
