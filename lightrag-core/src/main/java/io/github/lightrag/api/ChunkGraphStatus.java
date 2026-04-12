package io.github.lightrag.api;

public enum ChunkGraphStatus {
    NOT_MATERIALIZED,
    MATERIALIZED,
    PARTIAL,
    FAILED,
    STALE,
    MISSING
}
