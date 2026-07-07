package com.ligero.jdbc;

import java.sql.SQLException;

/** Unchecked wrapper for a failed statement (keeps the SQL in the message). */
public final class JdbcException extends RuntimeException {
    public JdbcException(String sql, SQLException cause) {
        super("SQL failed: " + sql, cause);
    }
}
