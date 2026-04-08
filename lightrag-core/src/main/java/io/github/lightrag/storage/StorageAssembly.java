package io.github.lightrag.storage;

import java.util.Objects;

public final class StorageAssembly {
    private final RelationalStorageAdapter relationalAdapter;
    private final GraphStorageAdapter graphAdapter;
    private final VectorStorageAdapter vectorAdapter;

    private StorageAssembly(
        RelationalStorageAdapter relationalAdapter,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        this.relationalAdapter = Objects.requireNonNull(relationalAdapter, "relationalAdapter");
        this.graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
        this.vectorAdapter = Objects.requireNonNull(vectorAdapter, "vectorAdapter");
    }

    public static Builder builder() {
        return new Builder();
    }

    public AtomicStorageProvider toStorageProvider() {
        return new StorageCoordinator(relationalAdapter, graphAdapter, vectorAdapter);
    }

    public static final class Builder {
        private RelationalStorageAdapter relationalAdapter;
        private GraphStorageAdapter graphAdapter;
        private VectorStorageAdapter vectorAdapter;

        private Builder() {
        }

        public Builder relationalAdapter(RelationalStorageAdapter relationalAdapter) {
            this.relationalAdapter = Objects.requireNonNull(relationalAdapter, "relationalAdapter");
            return this;
        }

        public Builder graphAdapter(GraphStorageAdapter graphAdapter) {
            this.graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
            return this;
        }

        public Builder vectorAdapter(VectorStorageAdapter vectorAdapter) {
            this.vectorAdapter = Objects.requireNonNull(vectorAdapter, "vectorAdapter");
            return this;
        }

        public StorageAssembly build() {
            return new StorageAssembly(relationalAdapter, graphAdapter, vectorAdapter);
        }
    }
}
