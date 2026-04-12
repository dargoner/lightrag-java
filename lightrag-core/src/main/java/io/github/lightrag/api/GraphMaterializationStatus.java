package io.github.lightrag.api;

public enum GraphMaterializationStatus {
    NOT_STARTED,
    MERGING,
    PARTIAL,
    MERGED,
    FAILED,
    STALE,
    MISSING
}
