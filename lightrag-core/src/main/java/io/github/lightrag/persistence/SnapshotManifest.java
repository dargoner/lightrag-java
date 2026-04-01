package io.github.lightrag.persistence;

import java.util.Objects;

public record SnapshotManifest(int schemaVersion, String createdAt, String payloadFile) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

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
