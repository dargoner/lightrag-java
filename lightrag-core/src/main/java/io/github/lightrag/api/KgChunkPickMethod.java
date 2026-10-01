package io.github.lightrag.api;

/**
 * How KG-retrieved entities pick their related text chunks, mirroring the upstream
 * {@code kg_chunk_pick_method} configuration.
 */
public enum KgChunkPickMethod {
    WEIGHT,
    VECTOR
}
