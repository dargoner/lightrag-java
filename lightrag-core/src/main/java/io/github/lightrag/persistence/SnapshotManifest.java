package io.github.lightrag.persistence;

import java.util.Objects;

/**
 * Manifest describing a file snapshot.
 *
 * <p>Schema version history:
 * <ul>
 *   <li>1 - payload without entity/relation attributes; attributes read as empty</li>
 *   <li>2 - payload carries the {@code attributes} map on graph and extracted records</li>
 * </ul>
 *
 * <p>New snapshots are always written with {@link #CURRENT_SCHEMA_VERSION}; loading accepts
 * every version from {@link #LEGACY_SCHEMA_VERSION} through {@link #CURRENT_SCHEMA_VERSION}
 * and rejects newer versions instead of silently degrading.
 */
public record SnapshotManifest(int schemaVersion, String createdAt, String payloadFile) {
    /** Oldest manifest version this SDK can still read. */
    public static final int LEGACY_SCHEMA_VERSION = 1;
    /** Version written by this SDK. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** Whether a payload with this manifest version carries attribute maps. */
    public static boolean carriesAttributes(int schemaVersion) {
        return schemaVersion >= 2;
    }

    public SnapshotManifest {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt").strip();
        if (createdAt.isEmpty()) {
            throw new IllegalArgumentException("createdAt must not be blank");
        }
        payloadFile = Objects.requireNonNull(payloadFile, "payloadFile").strip();
        if (payloadFile.isEmpty()) {
            throw new IllegalArgumentException("payloadFile must not be blank");
        }
    }
}
