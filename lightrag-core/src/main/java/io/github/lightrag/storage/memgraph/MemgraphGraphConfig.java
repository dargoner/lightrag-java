package io.github.lightrag.storage.memgraph;

import java.net.URI;
import java.util.Objects;

/**
 * Connection settings for a Memgraph instance over Bolt.
 *
 * <p>Memgraph ships without authentication by default, so {@code username} and {@code password}
 * may be blank; {@code database} defaults to {@code memgraph}, the fixed database name of the
 * community edition.</p>
 */
public record MemgraphGraphConfig(
    String boltUri,
    String username,
    String password,
    String database
) {
    public static final String DEFAULT_DATABASE = "memgraph";

    public MemgraphGraphConfig {
        boltUri = requireBoltUri(boltUri);
        username = username == null ? "" : username;
        password = Objects.requireNonNull(password, "password");
        database = requireNonBlank(database, "database");
    }

    /** Config for a no-auth Memgraph instance on its default database. */
    public MemgraphGraphConfig(String boltUri) {
        this(boltUri, "", "", DEFAULT_DATABASE);
    }

    private static String requireBoltUri(String value) {
        var normalized = requireNonBlank(value, "boltUri");
        var uri = URI.create(normalized);
        if (!"bolt".equals(uri.getScheme())) {
            throw new IllegalArgumentException("boltUri must use the bolt scheme");
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
