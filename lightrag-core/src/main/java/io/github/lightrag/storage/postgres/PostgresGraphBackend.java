package io.github.lightrag.storage.postgres;

/**
 * Graph backend for the PostgreSQL-based storage providers.
 *
 * <p>Valid values depend on the storage type:</p>
 * <ul>
 *   <li>{@code POSTGRES} ({@link PostgresStorageProvider}): {@link #TABLE} (default) stores the
 *       knowledge graph in native SQL tables; {@link #AGE} stores it in an Apache AGE graph in the
 *       same PostgreSQL database, mirroring the upstream Python {@code PGGraphStorage} layout.</li>
 *   <li>{@code POSTGRES_MILVUS_NEO4J} ({@link PostgresMilvusNeo4jStorageProvider}): {@link #NEO4J}
 *       (default) keeps the Neo4j graph projection; {@link #AGE} replaces it with an Apache AGE
 *       graph in the same PostgreSQL database.</li>
 * </ul>
 *
 * <p>{@link #AGE} requires the {@code age} extension to be installable on the server; the graph,
 * its labels, and its indexes are bootstrapped per workspace on provider construction. Neither
 * {@code TABLE} nor {@code NEO4J} are available for the other type; invalid combinations are
 * rejected with {@link IllegalArgumentException}.</p>
 */
public enum PostgresGraphBackend {
    TABLE,
    AGE,
    NEO4J
}
