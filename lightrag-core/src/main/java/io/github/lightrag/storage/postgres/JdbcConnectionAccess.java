package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

final class JdbcConnectionAccess {
    private final ConnectionFactory connectionFactory;
    private final boolean closeAfterUse;

    private JdbcConnectionAccess(ConnectionFactory connectionFactory, boolean closeAfterUse) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.closeAfterUse = closeAfterUse;
    }

    static JdbcConnectionAccess forDataSource(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbcConnectionAccess(dataSource::getConnection, true);
    }

    static JdbcConnectionAccess forConnection(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        return new JdbcConnectionAccess(() -> connection, false);
    }

    <T> T withConnection(SqlFunction<T> function) {
        Objects.requireNonNull(function, "function");
        if (closeAfterUse) {
            try (Connection connection = connectionFactory.open()) {
                return function.apply(connection);
            } catch (SQLException exception) {
                throw new StorageException("JDBC operation failed", exception);
            }
        }

        try {
            return function.apply(connectionFactory.open());
        } catch (SQLException exception) {
            throw new StorageException("JDBC operation failed", exception);
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
