package io.github.lightragjava.evaluation;

import java.util.Locale;

public enum RagasStorageProfile {
    IN_MEMORY,
    POSTGRES_NEO4J_TESTCONTAINERS;

    public static RagasStorageProfile fromValue(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "in-memory", "in_memory" -> IN_MEMORY;
            case "postgres-neo4j-testcontainers", "postgres_neo4j_testcontainers" -> POSTGRES_NEO4J_TESTCONTAINERS;
            default -> throw new IllegalArgumentException("Unsupported storage profile: " + value);
        };
    }
}
