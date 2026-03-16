package io.github.lightragjava.storage.postgres;

import io.github.lightragjava.exception.StorageException;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

final class PostgresAdvisoryLockManager {
    private static final String ACQUIRE_EXCLUSIVE_SQL = "SELECT pg_advisory_lock(?)";
    private static final String RELEASE_EXCLUSIVE_SQL = "SELECT pg_advisory_unlock(?)";
    private static final String ACQUIRE_SHARED_SQL = "SELECT pg_advisory_lock_shared(?)";
    private static final String RELEASE_SHARED_SQL = "SELECT pg_advisory_unlock_shared(?)";

    private final DataSource dataSource;
    private final long lockKey;

    PostgresAdvisoryLockManager(DataSource dataSource, PostgresStorageConfig config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(config, "config");
        this.lockKey = deriveLockKey(config.schema() + ":" + config.tablePrefix());
    }

    <T> T withSharedLock(RuntimeSupplier<T> supplier) {
        return withLock(ACQUIRE_SHARED_SQL, RELEASE_SHARED_SQL, supplier);
    }

    void withExclusiveLock(Runnable runnable) {
        withLock(ACQUIRE_EXCLUSIVE_SQL, RELEASE_EXCLUSIVE_SQL, () -> {
            runnable.run();
            return null;
        });
    }

    <T> T withExclusiveLock(RuntimeSupplier<T> supplier) {
        return withLock(ACQUIRE_EXCLUSIVE_SQL, RELEASE_EXCLUSIVE_SQL, supplier);
    }

    private <T> T withLock(String acquireSql, String releaseSql, RuntimeSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (Connection connection = dataSource.getConnection()) {
            acquire(connection, acquireSql);
            Throwable primaryFailure = null;
            try {
                return supplier.get();
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                release(connection, releaseSql, primaryFailure);
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to coordinate PostgreSQL advisory lock", exception);
        }
    }

    private void acquire(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            statement.execute();
        }
    }

    private void release(Connection connection, String sql, Throwable primaryFailure) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !resultSet.getBoolean(1)) {
                    throw new StorageException("PostgreSQL advisory lock was not held during release");
                }
            }
        } catch (SQLException | StorageException exception) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(exception);
                return;
            }
            throw new StorageException("Failed to release PostgreSQL advisory lock", exception);
        }
    }

    private static long deriveLockKey(String namespace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(namespace.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    @FunctionalInterface
    interface RuntimeSupplier<T> {
        T get();
    }
}
