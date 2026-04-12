package io.github.lightrag.api;

public enum TaskType {
    INGEST_DOCUMENTS,
    INGEST_SOURCES,
    REBUILD_GRAPH,
    MATERIALIZE_DOCUMENT_GRAPH,
    MATERIALIZE_CHUNK_GRAPH
}
