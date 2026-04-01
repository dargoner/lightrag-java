package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresRetrySupportTest {
    @Test
    void retriesTransientFailuresUntilOperationSucceeds() {
        var attempts = new AtomicInteger();
        var sleeps = new AtomicInteger();

        var result = PostgresRetrySupport.execute("test operation", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new StorageException(
                    "transient serialization conflict",
                    new SQLTransactionRollbackException("serialization failure", "40001")
                );
            }
            return "ok";
        }, millis -> sleeps.incrementAndGet());

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).hasValue(2);
    }

    @Test
    void doesNotRetryPermanentFailures() {
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> PostgresRetrySupport.execute("test operation", () -> {
            attempts.incrementAndGet();
            throw new StorageException("duplicate key", new SQLException("duplicate key", "23505"));
        }, millis -> {
        }))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("duplicate key");

        assertThat(attempts).hasValue(1);
    }
}
