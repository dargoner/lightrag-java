package io.github.lightrag.storage.nebula;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Connection settings for a NebulaGraph cluster.
 *
 * <p>All workspaces of one deployment share a single space (default {@code lightrag}) and are kept
 * apart by the {@code workspace_id} property on every vertex and edge, the same shared-graph model
 * the Neo4j backend uses. The space, its schema and its indexes are created idempotently by
 * {@link NebulaGraphStore} on first use, so a fresh cluster only needs this config.</p>
 */
public record NebulaGraphConfig(
    String host,
    int port,
    String username,
    String password,
    String space,
    int partitionNum,
    int replicaFactor
) {
    public static final int DEFAULT_PORT = 9669;
    public static final String DEFAULT_USERNAME = "root";
    public static final String DEFAULT_PASSWORD = "nebula";
    public static final String DEFAULT_SPACE = "lightrag";

    /** The space name goes into DDL where a plain identifier is required. */
    private static final Pattern SPACE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public NebulaGraphConfig {
        host = requireNonBlank(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be within 1..65535");
        }
        username = requireNonBlank(username, "username");
        Objects.requireNonNull(password, "password");
        if (password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        space = requireNonBlank(space, "space");
        if (!SPACE_NAME.matcher(space).matches()) {
            throw new IllegalArgumentException("space must match " + SPACE_NAME.pattern() + ": " + space);
        }
        if (partitionNum <= 0) {
            throw new IllegalArgumentException("partitionNum must be positive");
        }
        if (replicaFactor <= 0) {
            throw new IllegalArgumentException("replicaFactor must be positive");
        }
    }

    /** Config for a default NebulaGraph instance with the bundled {@code root} account. */
    public NebulaGraphConfig(String host) {
        this(host, DEFAULT_PORT, DEFAULT_USERNAME, DEFAULT_PASSWORD, DEFAULT_SPACE, 1, 1);
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
