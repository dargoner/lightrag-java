package io.github.lightrag.model;

/**
 * How the query engine reacts when a configured {@link RerankModel} throws during query execution.
 */
public enum RerankFailureMode {
    /** Propagate the reranker failure to the caller (default). */
    FAIL_FAST,
    /**
     * Log a warning and keep the original retrieval order, mirroring upstream
     * {@code utils.py:7013-7021}.
     */
    FALLBACK_TO_ORIGINAL
}
