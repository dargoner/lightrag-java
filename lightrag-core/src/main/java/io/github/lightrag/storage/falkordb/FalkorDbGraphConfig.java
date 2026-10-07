package io.github.lightrag.storage.falkordb;

import java.util.Objects;

/**
 * Connection settings for a FalkorDB instance.
 *
 * <p>FalkorDB ships without authentication by default, so {@code username} and {@code password}
 * may be blank; a blank username selects the unauthenticated driver form.</p>
 */
public record FalkorDbGraphConfig(
    String host,
    int port,
    String username,
    String password
) {
    public static final int DEFAULT_PORT = 6379;

    public FalkorDbGraphConfig {
        host = requireNonBlank(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be within 1..65535");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    /** Config for a no-auth FalkorDB instance on its default port. */
    public FalkorDbGraphConfig(String host) {
        this(host, DEFAULT_PORT, "", "");
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
