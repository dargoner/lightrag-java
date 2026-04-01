package io.github.lightrag.storage.mysql;

import io.github.lightrag.exception.StorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

final class MySqlJdbcConnectionAccess {
    private final ConnectionFactory connectionFactory;
    private final boolean closeAfterUse;

    private MySqlJdbcConnectionAccess(ConnectionFactory connectionFactory, boolean closeAfterUse) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.closeAfterUse = closeAfterUse;
    }

    static MySqlJdbcConnectionAccess forDataSource(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new MySqlJdbcConnectionAccess(dataSource::getConnection, true);
    }

    static MySqlJdbcConnectionAccess forConnection(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        return new MySqlJdbcConnectionAccess(() -> connection, false);
    }

    <T> T withConnection(SqlFunction<T> function) {
        Objects.requireNonNull(function, "function");
        if (closeAfterUse) {
            try (var connection = connectionFactory.open()) {
                return function.apply(connection);
            } catch (SQLException exception) {
                throw new StorageException("MySQL JDBC operation failed", exception);
            }
        }

        try {
            return function.apply(connectionFactory.open());
        } catch (SQLException exception) {
            throw new StorageException("MySQL JDBC operation failed", exception);
        }
    }

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionFactory {
        Connection open() throws SQLException;
    }
}
