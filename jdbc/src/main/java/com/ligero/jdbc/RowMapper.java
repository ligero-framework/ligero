package com.ligero.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a {@link ResultSet} to a {@code T} (usually a record). */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet row) throws SQLException;
}
