package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.StorageLockManager;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PostgresAdvisoryLockManager implements StorageLockManager {
    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLockManager.class);
    private static final String ACQUIRE_EXCLUSIVE_SQL = "SELECT pg_advisory_lock(?)";
    private static final String RELEASE_EXCLUSIVE_SQL = "SELECT pg_advisory_unlock(?)";
    private static final String ACQUIRE_SHARED_SQL = "SELECT pg_advisory_lock_shared(?)";
    private static final String RELEASE_SHARED_SQL = "SELECT pg_advisory_unlock_shared(?)";
    private static final long SLOW_LOCK_WAIT_MILLIS = 1_000L;

    private final DataSource dataSource;
    private final long lockKey;

    PostgresAdvisoryLockManager(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    PostgresAdvisoryLockManager(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(config, "config");
        this.lockKey = deriveLockKey(config.schema() + ":" + config.tablePrefix() + ":" + Objects.requireNonNull(workspaceId, "workspaceId"));
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

    @Override
    public <T> T withExclusiveLock(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return withLock(ACQUIRE_EXCLUSIVE_SQL, RELEASE_EXCLUSIVE_SQL, supplier::get);
    }

    private <T> T withLock(String acquireSql, String releaseSql, RuntimeSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (Connection connection = dataSource.getConnection()) {
            long acquireStarted = System.nanoTime();
            acquire(connection, acquireSql);
            long acquiredAt = System.nanoTime();
            long waitMillis = elapsedMillis(acquireStarted, acquiredAt);
            if (waitMillis >= SLOW_LOCK_WAIT_MILLIS) {
                log.info(
                    "LightRAG PostgreSQL advisory lock acquired slowly: mode={}, lockKey={}, waitMs={}",
                    lockMode(acquireSql),
                    lockKey,
                    waitMillis
                );
            }
            Throwable primaryFailure = null;
            try {
                return supplier.get();
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                long heldMillis = elapsedMillis(acquiredAt, System.nanoTime());
                release(connection, releaseSql, primaryFailure);
                log.info(
                    "LightRAG PostgreSQL advisory lock released: mode={}, lockKey={}, waitMs={}, heldMs={}",
                    lockMode(acquireSql),
                    lockKey,
                    waitMillis,
                    heldMillis
                );
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

    private static long elapsedMillis(long startedNanos, long endedNanos) {
        return Math.max(0L, (endedNanos - startedNanos) / 1_000_000L);
    }

    private static String lockMode(String acquireSql) {
        return ACQUIRE_SHARED_SQL.equals(acquireSql) ? "shared" : "exclusive";
    }
}
