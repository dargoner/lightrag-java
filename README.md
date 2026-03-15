# lightrag-java

Standalone Java SDK for a LightRAG-style indexing and retrieval pipeline.

## Requirements

- A local JDK 21 is supported.
- If JDK 21 is not installed, Gradle is configured to auto-provision a matching toolchain.

## Quick Start

```java
var storage = InMemoryStorageProvider.create();
var rag = LightRag.builder()
    .chatModel(new OpenAiCompatibleChatModel(
        "https://api.openai.com/v1/",
        "gpt-4o-mini",
        System.getenv("OPENAI_API_KEY")
    ))
    .embeddingModel(new OpenAiCompatibleEmbeddingModel(
        "https://api.openai.com/v1/",
        "text-embedding-3-small",
        System.getenv("OPENAI_API_KEY")
    ))
    .storage(storage)
    .build();

rag.ingest(List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .build());

System.out.println(result.answer());
```

For tests, demos, and ephemeral runs, the in-memory provider is still the fastest option. For restart-safe ingestion and durable local state, use the PostgreSQL provider described below.

## PostgreSQL Storage

`PostgresStorageProvider` stores documents, chunks, graph records, and vectors in PostgreSQL so the SDK can survive process restarts without relying on JSON snapshots as the primary data store.

### Prerequisites

- PostgreSQL 16+ with the `vector` extension available
- A database user that can create tables in the target schema
- A configured `SnapshotStore` for `loadFromSnapshot(...)` and autosave flows

For local development and Testcontainers, `pgvector/pgvector:pg16` is the expected image because it already includes the `vector` extension.

### Quick Start

```java
var storage = new PostgresStorageProvider(
    new PostgresStorageConfig(
        "jdbc:postgresql://localhost:5432/lightrag",
        "postgres",
        "postgres",
        "public",
        1536,
        "rag_"
    ),
    new FileSnapshotStore()
);

var rag = LightRag.builder()
    .chatModel(new OpenAiCompatibleChatModel(
        "https://api.openai.com/v1/",
        "gpt-4o-mini",
        System.getenv("OPENAI_API_KEY")
    ))
    .embeddingModel(new OpenAiCompatibleEmbeddingModel(
        "https://api.openai.com/v1/",
        "text-embedding-3-small",
        System.getenv("OPENAI_API_KEY")
    ))
    .storage(storage)
    .build();

rag.ingest(List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "postgres-demo"))
));
```

`PostgresStorageProvider` bootstraps its schema automatically on startup. Ingest writes run atomically across document, chunk, graph, and vector storage.

## Snapshot Usage

```java
var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
var snapshotPath = Path.of("snapshots", "repository.snapshot.json");

var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .loadFromSnapshot(snapshotPath)
    .build();

rag.ingest(documents);
```

`loadFromSnapshot(...)` does two things:
- If the snapshot file already exists, it restores documents, chunks, graph data, and vectors before `build()`.
- It also sets the autosave target used after successful `ingest(...)` calls.

With the PostgreSQL backend, snapshots remain delegated to the configured `SnapshotStore`. PostgreSQL is the primary durable store for online data, while snapshot files are still used only when you explicitly load or autosave snapshots through the existing API.

## Current v1 Scope

- Bundled storage providers: in-memory and PostgreSQL.
- PostgreSQL is the current bundled durable backend for documents, chunks, graph data, and vectors.
- Snapshot persistence still uses the `SnapshotStore` SPI and remains file-based by default.
- Query modes supported today: `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`.
- Extraction and graph merge rules are intentionally simple and deterministic.
- OpenAI-compatible adapters support standard `/chat/completions` and `/embeddings` endpoints.
- Neo4j support is planned for the next storage phase and is not part of the current bundled implementation.
