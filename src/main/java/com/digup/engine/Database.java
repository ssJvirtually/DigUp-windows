package com.digup.engine;

import java.io.Closeable;
import java.sql.*;

/**
 * SQLite JDBC connection wrapper with WAL mode, foreign keys, and busy timeout.
 */
public final class Database implements Closeable {
    private final String url;
    private Connection connection;

    public interface RowCallback {
        void accept(ResultSet rs) throws SQLException;
    }

    public interface TransactionCallable<T> {
        T call() throws Exception;
    }

    public Database(String path) throws SQLException {
        this.url = "jdbc:sqlite:" + path;
        this.connection = DriverManager.getConnection(url);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000;");
            st.execute("PRAGMA journal_mode = WAL;");
            st.execute("PRAGMA synchronous = NORMAL;");
            st.execute("PRAGMA foreign_keys = ON;");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    public void run(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    public void query(String sql, RowCallback callback, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    callback.accept(rs);
                }
            }
        }
    }

    public long getLastInsertId() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public <T> T transaction(TransactionCallable<T> callable) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = callable.call();
            connection.commit();
            return result;
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public void transaction(Runnable runnable) throws Exception {
        transaction(() -> {
            runnable.run();
            return null;
        });
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) {
                ps.setNull(idx, Types.NULL);
            } else if (p instanceof Integer) {
                ps.setInt(idx, (Integer) p);
            } else if (p instanceof Long) {
                ps.setLong(idx, (Long) p);
            } else if (p instanceof Double) {
                ps.setDouble(idx, (Double) p);
            } else if (p instanceof Float) {
                ps.setFloat(idx, (Float) p);
            } else if (p instanceof String) {
                ps.setString(idx, (String) p);
            } else if (p instanceof byte[]) {
                ps.setBytes(idx, (byte[]) p);
            } else if (p instanceof Boolean) {
                ps.setInt(idx, (Boolean) p ? 1 : 0);
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
        }
    }
}
