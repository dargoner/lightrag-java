package io.github.lightrag.storage.postgres;

/**
 * Physical vector type backing the PostgreSQL storage family. {@link #AUTO} keeps the historical
 * pgvector behavior until the provider resolves the type from the live database.
 */
public enum PostgresVectorDialect {
    AUTO,
    PGVECTOR,
    VASTBASE;

    public PostgresVectorDialect resolved() {
        return this == AUTO ? PGVECTOR : this;
    }

    public boolean requiresVectorExtension() {
        return resolved() == PGVECTOR;
    }

    public String columnType(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        return switch (resolved()) {
            case PGVECTOR -> "vector(" + dimensions + ")";
            case VASTBASE -> "floatvector(" + dimensions + ")";
            case AUTO -> throw new IllegalStateException("AUTO dialect must be resolved before use");
        };
    }

    public String innerProductOperatorClass() {
        return switch (resolved()) {
            case PGVECTOR -> "vector_ip_ops";
            case VASTBASE -> "floatvector_ip_ops";
            case AUTO -> throw new IllegalStateException("AUTO dialect must be resolved before use");
        };
    }
}
