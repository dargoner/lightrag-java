package io.github.lightrag.api;

public enum TaskStage {
    PREPARING,
    PARSING,
    CHUNKING,
    PRIMARY_EXTRACTION,
    REFINEMENT_EXTRACTION,
    GRAPH_ASSEMBLY,
    VECTOR_INDEXING,
    COMMITTING,
    COMPLETED
}
