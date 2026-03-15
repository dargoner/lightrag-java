# Java LightRAG Production Storage Design

## Overview

This document defines the next storage-focused phase for the Java LightRAG SDK after the v1 in-memory release.

The current SDK is functionally complete for local development, but it still has one major limitation: all primary indexes live in memory. That makes the current implementation useful for correctness and API validation, but not for long-lived datasets, restart-safe ingestion, or realistic deployment topologies.

The next phase should therefore focus on a production-grade storage foundation rather than expanding retrieval modes or model coverage.

## Goals

- Add a durable storage backend suitable for real datasets and restart-safe operation.
- Preserve the existing `LightRag`, `StorageProvider`, and retrieval APIs.
- Keep provider-level atomic ingest semantics on the primary durable backend.
- Support vector similarity through PostgreSQL with the `pgvector` extension.
- Create a clean architectural seam for a future `Neo4j` graph backend.

## Non-Goals

- Do not implement cross-database distributed transactions in this phase.
- Do not add HTTP services, deployment manifests, or operational control planes.
- Do not redesign query mode semantics.
- Do not make `Neo4j` mandatory for the first durable backend release.
- Do not replace the in-memory provider; keep it as the fast default for tests and local demos.

## Scope Decomposition

The storage roadmap naturally breaks into two sub-projects:

### Sub-Project A: PostgreSQL Production Storage Foundation

This phase adds a single durable backend that can satisfy the current `AtomicStorageProvider` contract without distributed coordination:

- `PostgresDocumentStore`
- `PostgresChunkStore`
- `PostgresGraphStore`
- `PostgresVectorStore` backed by `pgvector`
- `PostgresStorageProvider`

This phase is the one covered by the implementation plan written alongside this document.

### Sub-Project B: Neo4j Graph Backend

This phase adds:

- `Neo4jGraphStore`
- a mixed provider strategy or graph projection mode
- a decision on transactional consistency between relational source-of-truth data and the graph view

This is intentionally deferred. The current provider contract assumes provider-wide atomic writes. Mixing PostgreSQL and Neo4j immediately would force distributed transaction or compensation design before we have a durable baseline.

## Approach Options

### Recommended: PostgreSQL As The First Durable Provider

Use PostgreSQL as the single transactional storage engine for documents, chunks, graph records, and vector embeddings. Store vectors through `pgvector`, and keep the graph model in relational tables for now.

Benefits:

- preserves provider-level atomic ingest with one database transaction
- gives durable document, chunk, graph, and vector storage immediately
- avoids designing distributed consistency before there is operational pressure to do so
- creates a low-risk baseline that the existing tests and query strategies can target

Trade-offs:

- graph traversals remain relational rather than native graph-database queries
- Neo4j-specific capabilities are postponed to the next phase

### Alternative: Mixed PostgreSQL + Neo4j Provider Now

Use PostgreSQL for documents, chunks, and vectors, while writing graph data directly to Neo4j.

Benefits:

- reaches the long-term target architecture sooner
- enables native graph traversals earlier

Trade-offs:

- breaks the simplicity of the current `AtomicStorageProvider` contract
- requires distributed transaction design, compensation, or eventual consistency
- significantly raises implementation and test complexity for the next phase

### Alternative: PostgreSQL-Only Permanent Direction

Use PostgreSQL for all durable stores and never add a graph database.

Benefits:

- smallest operational footprint
- easiest transactional model

Trade-offs:

- removes the clean path to a graph-native backend
- does not align with the user’s original desire to support a graph database backend when needed

## Recommended Design

The next implementation phase should deliver Sub-Project A: a PostgreSQL-backed durable provider with `pgvector`, while preserving a clean graph backend seam for Sub-Project B.

## Architecture

### Storage SPI

Keep the existing public abstractions:

- `StorageProvider`
- `AtomicStorageProvider`
- `DocumentStore`
- `ChunkStore`
- `GraphStore`
- `VectorStore`
- `SnapshotStore`

No public API redesign is needed for this phase. The durable backend should conform to the existing SPI surface.

### PostgreSQL Backend Package

Add a backend-specific package:

```text
io.github.lightragjava.storage.postgres
```

Recommended components:

- `PostgresStorageProvider`
- `PostgresDocumentStore`
- `PostgresChunkStore`
- `PostgresGraphStore`
- `PostgresVectorStore`
- `PostgresSchemaManager`
- `JdbcJsonCodec`

This keeps JDBC and schema concerns isolated from the rest of the SDK.

The provider should continue delegating snapshot persistence through the existing `SnapshotStore` SPI rather than introducing database-backed snapshot manifests in this phase.

### Transaction Model

`PostgresStorageProvider` should implement `AtomicStorageProvider` using one JDBC transaction per atomic write.

Required behavior:

- begin transaction
- keep stable top-level store facades for normal read paths
- expose store instances bound to the same connection
- commit on success
- rollback on failure
- support `restore(Snapshot)` for builder-side snapshot loading

This preserves the contract already relied on by ingestion and snapshot restore flows.

The transaction-bound stores returned inside `writeAtomically(...)` should be distinct from the stable top-level provider facades. That keeps `StorageProvider` behavior aligned with the existing singleton-style provider getters while still letting atomic operations share one JDBC connection.

Achieving true provider-level ingest atomicity also requires an indexing-path change: chunk vectors, graph records, and graph vectors must be persisted inside the same atomic block rather than split across separate top-level writes.

### Relational Graph Model

Use relational tables for graph storage in this phase.

Recommended schema shape:

- `rag_documents`
- `rag_chunks`
- `rag_entities`
- `rag_entity_aliases`
- `rag_entity_chunks`
- `rag_relations`
- `rag_relation_chunks`
- `rag_vectors`

The graph tables should preserve the current `GraphStore` contract:

- load entity or relation by ID
- list all entities and relations
- find one-hop relations for an entity

### Vector Model

Store vector rows in PostgreSQL using `pgvector`.

Requirements:

- namespace-aware storage
- deterministic row IDs
- top-K similarity search
- exact inner-product ordering for v1 parity with the current in-memory implementation

The backend should continue honoring the current `VectorStore` API.

### Schema Management

Use SDK-managed bootstrap SQL in this phase rather than a separate migration framework.

Requirements:

- create tables on first use
- create `vector` extension if missing
- create required indexes
- keep schema bootstrap idempotent

This is intentionally lightweight. A migration framework can be introduced later if the schema becomes operationally complex.

## API Impact

### Builder

The builder should remain:

```java
LightRag.builder()
    .storage(storageProvider)
```

The new durable provider should be opt-in and coexist with the current in-memory provider.

### Configuration

The storage backend will need connection details. The cleanest shape is to add a backend-specific configuration object such as:

- `PostgresStorageConfig`

This should carry:

- JDBC URL
- username
- password
- schema name
- vector dimensions
- optional table prefix

`PostgresStorageProvider` should also accept a delegated `SnapshotStore` instance. In this phase, snapshot manifests and payloads should remain outside PostgreSQL so the provider can reuse the existing file-based snapshot implementation or any future alternative without changing the storage SPI.

### Read/Write Behavior

No query-mode behavior should change. The goal is behavioral parity with the current in-memory provider, not a new retrieval model.

## Testing Strategy

The next phase should add integration-focused tests rather than only unit tests.

Required coverage:

- schema bootstrap on empty database
- document and chunk persistence
- vector write and similarity search
- graph write and one-hop retrieval
- provider-level rollback on ingest failure
- end-to-end rollback when a later ingest phase fails after earlier writes
- snapshot restore into PostgreSQL-backed storage
- end-to-end ingest and query parity against the durable provider

Use Testcontainers-backed PostgreSQL integration tests for these behaviors.

To keep `CREATE EXTENSION vector` deterministic, the test suite should run against a pgvector-enabled PostgreSQL image rather than assuming the stock `postgres` image already has the extension installed.

## Risks

### SQL And Graph Parity Drift

The relational graph backend must behave the same way as the current in-memory graph store. Query tests need to assert parity rather than only “non-empty” results.

### Vector Distance Mismatch

`pgvector` search semantics must match the current Java-side scoring assumptions closely enough that retrieval ordering stays stable for tests.

### Restore Semantics

The new provider must support full snapshot restore without leaking partial state. This should be tested at the provider level, not only through builder smoke tests.

### Snapshot Boundary Confusion

The durable PostgreSQL backend is the source of truth for live indexes, but snapshots remain a separate export/import concern behind `SnapshotStore`. The implementation should keep that boundary explicit so database persistence and snapshot persistence do not get conflated.

## Future Follow-Up

After the PostgreSQL production foundation is complete, the next design should cover:

- `Neo4jGraphStore`
- a mixed or projected storage provider strategy
- consistency semantics between transactional relational writes and graph projection
- query-path use of native graph traversal when available
