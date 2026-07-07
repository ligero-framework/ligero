package com.ligero.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A tiny, explicit data helper over a {@link DataSource}: you write the SQL, it
 * maps rows to your records and manages connections and transactions. No ORM,
 * no reflection, no session-in-view.
 *
 * <pre>{@code
 * Jdbc db = new Jdbc(dataSource);
 *
 * record Product(long id, String name) {}
 * RowMapper<Product> asProduct = r -> new Product(r.getLong("id"), r.getString("name"));
 *
 * List<Product>     all = db.query("select id, name from products order by id", asProduct);
 * Optional<Product> one = db.queryOne("select id, name from products where id = ?", asProduct, 7);
 * long              id  = db.insert("insert into products(name) values (?)", "Keyboard");
 * int              rows = db.update("update products set name = ? where id = ?", "Mouse", id);
 *
 * // a transaction: commit on success, rollback on any exception
 * db.tx(tx -> {
 *     long pid = tx.insert("insert into products(name) values (?)", "Bundle");
 *     tx.update("insert into stock(product_id, qty) values (?, ?)", pid, 100);
 *     return pid;
 * });
 * }</pre>
 */
public final class Jdbc implements SqlOps {

    private final DataSource dataSource;

    public Jdbc(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            return SqlOps.runQuery(c, sql, mapper, params);
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int update(String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            return SqlOps.runUpdate(c, sql, params);
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    @Override
    public long insert(String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            return SqlOps.runInsert(c, sql, params);
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    /** Runs {@code work} in a transaction: commit on success, rollback on any exception. */
    public <T> T tx(Function<Tx, T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean previous = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(new Tx(c));
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw new JdbcException("transaction", e);
        }
    }

    /** Transaction-scoped operations, sharing one {@link Connection}. */
    public static final class Tx implements SqlOps {
        private final Connection connection;

        Tx(Connection connection) {
            this.connection = connection;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            return SqlOps.runQuery(connection, sql, mapper, params);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            List<T> rows = query(sql, mapper, params);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        @Override
        public int update(String sql, Object... params) {
            return SqlOps.runUpdate(connection, sql, params);
        }

        @Override
        public long insert(String sql, Object... params) {
            return SqlOps.runInsert(connection, sql, params);
        }
    }
}
