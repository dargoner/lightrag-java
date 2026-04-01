package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.Objects;

final class PostgresRetrySupport {
    private static final Logger log = LoggerFactory.getLogger(PostgresRetrySupport.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 100L;
    private static final long MAX_BACKOFF_MILLIS = 1_000L;

    private PostgresRetrySupport() {
    }

    static <T> T execute(String operation, RuntimeSupplier<T> supplier) {
        return execute(operation, supplier, PostgresRetrySupport::sleep);
    }

    static <T> T execute(String operation, RuntimeSupplier<T> supplier, Sleeper sleeper) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(sleeper, "sleeper");
        int attempt = 1;
        while (true) {
            try {
                return supplier.get();
            } catch (RuntimeException failure) {
                if (!isTransientFailure(failure) || attempt >= MAX_ATTEMPTS) {
                    throw failure;
                }
                long backoffMillis = backoffMillis(attempt);
                log.debug(
                    "Retrying PostgreSQL operation '{}' after transient failure (attempt {}/{}) in {} ms",
                    operation,
                    attempt + 1,
                    MAX_ATTEMPTS,
                    backoffMillis,
                    failure
                );
                sleeper.sleep(backoffMillis);
                attempt++;
            }
        }
    }

    static boolean isTransientFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && isTransientSqlException(sqlException)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientSqlException(SQLException exception) {
        if (exception instanceof SQLTransactionRollbackException) {
            return true;
        }
        String sqlState = exception.getSQLState();
        if (sqlState == null) {
            return false;
        }
        return switch (sqlState) {
            case "40001", "40P01", "55P03", "57014" -> true;
            default -> false;
        };
    }

    private static long backoffMillis(int attempt) {
        long computed = INITIAL_BACKOFF_MILLIS << Math.max(0, attempt - 1);
        return Math.min(MAX_BACKOFF_MILLIS, computed);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while retrying PostgreSQL operation", exception);
        }
    }

    @FunctionalInterface
    interface RuntimeSupplier<T> {
        T get();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }
}
