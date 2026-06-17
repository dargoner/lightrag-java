package io.github.lightrag.storage;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Coordinates storage writes across provider instances.
 */
@FunctionalInterface
public interface StorageLockManager {
    <T> T withExclusiveLock(Supplier<T> supplier);

    static StorageLockManager noop() {
        return new StorageLockManager() {
            @Override
            public <T> T withExclusiveLock(Supplier<T> supplier) {
                return Objects.requireNonNull(supplier, "supplier").get();
            }
        };
    }
}
