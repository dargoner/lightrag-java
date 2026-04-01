package io.github.lightrag.storage.mysql;

import io.github.lightrag.exception.StorageException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;

final class MySqlNamedLockManager {
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final DataSource dataSource;
    private final String lockName;

    MySqlNamedLockManager(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, "default");
    }

    MySqlNamedLockManager(DataSource dataSource, MySqlStorageConfig config, String workspaceId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(config, "config");
        this.lockName = deriveLockName(config.tablePrefix() + ":" + Objects.requireNonNull(workspaceId, "workspaceId"));
    }

    void withExclusiveLock(Runnable runnable) {
        withExclusiveLock(() -> {
            runnable.run();
            return null;
        });
    }

    <T> T withExclusiveLock(RuntimeSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (var connection = dataSource.getConnection()) {
            acquire(connection);
            Throwable primaryFailure = null;
            try {
                return supplier.get();
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                release(connection, primaryFailure);
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to coordinate MySQL named lock", exception);
        }
    }

    <T> T withSharedLock(RuntimeSupplier<T> supplier) {
        return withExclusiveLock(supplier);
    }

    private void acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, DEFAULT_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new StorageException("Timed out acquiring MySQL named lock: " + lockName);
                }
            }
        }
    }

    private void release(Connection connection, Throwable primaryFailure) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getObject(1) == null || resultSet.getInt(1) != 1) {
                    throw new StorageException("MySQL named lock was not held during release");
                }
            }
        } catch (SQLException | StorageException exception) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(exception);
                return;
            }
            throw new StorageException("Failed to release MySQL named lock", exception);
        }
    }

    private static String deriveLockName(String namespace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(namespace.getBytes(StandardCharsets.UTF_8));
            return "lightrag:" + HexFormat.of().formatHex(digest).substring(0, 55);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    @FunctionalInterface
    interface RuntimeSupplier<T> {
        T get();
    }
}
