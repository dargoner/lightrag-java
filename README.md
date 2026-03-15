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

## Current v1 Scope

- In-memory storage is the only bundled graph/vector/document storage implementation.
- Snapshot persistence is file-based JSON and intended for local workflows.
- Query modes supported today: `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`.
- Extraction and graph merge rules are intentionally simple and deterministic.
- OpenAI-compatible adapters support standard `/chat/completions` and `/embeddings` endpoints.
