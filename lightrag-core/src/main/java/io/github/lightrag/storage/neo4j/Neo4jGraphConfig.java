package io.github.lightrag.storage.neo4j;

import java.net.URI;
import java.util.Objects;

public record Neo4jGraphConfig(
    String boltUri,
    String username,
    String password,
    String database
) {
    public Neo4jGraphConfig {
        boltUri = requireUri(boltUri);
        username = requireNonBlank(username, "username");
        password = Objects.requireNonNull(password, "password");
        database = requireNonBlank(database, "database");
    }

    private static String requireUri(String value) {
        var normalized = requireNonBlank(value, "boltUri");
        var uri = URI.create(normalized);
        var scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("bolt") && !scheme.equals("neo4j"))) {
            throw new IllegalArgumentException("boltUri must use bolt or neo4j scheme");
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
