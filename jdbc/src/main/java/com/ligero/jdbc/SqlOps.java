package com.ligero.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The query/update operations, shared by {@link Jdbc} (a fresh connection per
 * call) and the transactional handle (one connection for the whole unit).
 */
public sealed interface SqlOps permits Jdbc, Jdbc.Tx {

    /** All matching rows, mapped. */
    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params);

    /** The single matching row, or empty. */
    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params);

    /** Executes an {@code INSERT/UPDATE/DELETE}, returning affected rows. */
    int update(String sql, Object... params);

    /** Executes an {@code INSERT} and returns the generated key. */
    long insert(String sql, Object... params);

    // ---- shared implementation over a Connection ----

    static <T> List<T> runQuery(Connection c, String sql, RowMapper<T> mapper, Object[] params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    static int runUpdate(Connection c, String sql, Object[] params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    static long runInsert(Connection c, String sql, Object[] params) {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new JdbcException(sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
