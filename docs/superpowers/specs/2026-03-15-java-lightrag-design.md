# Java LightRAG Design

## Overview

This document defines the first version of a standalone Java implementation inspired by LightRAG.

The implementation target is:

- a standalone Java repository
- a Java SDK, not an HTTP service
- capability equivalence rather than Python API compatibility
- LightRAG core behavior: document ingestion, chunking, entity and relation extraction, graph retrieval, vector retrieval, and `local` / `global` / `hybrid` / `mix` query modes
- a built-in OpenAI-compatible model adapter
- in-memory storage plus file-based persistence for the first version

The design favors stable interfaces and modular internals over maximizing first-pass feature breadth.

## Goals

- Provide a Java-first SDK that captures the core retrieval and knowledge graph ideas of LightRAG.
- Keep storage, model, and retrieval logic replaceable through narrow interfaces.
- Make the first version runnable without external databases.
- Preserve a path to future graph, vector, and KV backends without redesigning the SDK surface.

## Non-Goals

- Full Python LightRAG behavioral compatibility.
- HTTP server, web UI, or deployment platform in the first version.
- Workspace isolation, multi-tenant management, or operational control planes.
- Complex entity disambiguation across large corpora in the first version.
- Broad model-provider coverage in the first version.

## Approach Options

### Recommended: Layered Core With SPI Extensions

Structure the SDK into a small public facade plus internal modules for indexing, query, storage, and model access.

Benefits:

- allows a stable user-facing API
- keeps future storage integrations isolated
- reduces rewrite risk when adding persistence backends or service wrappers

Trade-offs:

- requires more up-front interface design than a monolithic implementation

### Alternative: Single-Module Monolith

Implement all capabilities behind one orchestrator with minimal abstraction.

Benefits:

- fastest initial implementation

Trade-offs:

- high coupling between indexing, graph assembly, retrieval, and persistence
- expensive to evolve once external backends are introduced

### Alternative: Workflow Engine First

Model the pipeline as resumable jobs and explicit workflow stages.

Benefits:

- strong fit for later batch orchestration and recovery

Trade-offs:

- too heavy for the first SDK release
- delays useful end-to-end delivery

## Architecture

The first version should use a layered architecture with a public facade and replaceable SPI boundaries.

### Public API Layer

Expose a single high-level entry point:

- `LightRag`
- `LightRagBuilder`
- `QueryRequest`
- `QueryResult`

Example shape:

```java
LightRag rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .build();

rag.ingest(documents);

QueryResult result = rag.query(
    QueryRequest.builder()
        .query("What are the main themes?")
        .mode(QueryMode.MIX)
        .build()
);
```

This layer must remain small and stable.

### Indexing Layer

Responsibilities:

- document ingestion
- chunking
- chunk embedding
- entity and relation extraction
- graph assembly and merge
- entity and relation embedding
- persistence checkpointing

Primary components:

- `DocumentIngestor`
- `Chunker`
- `KnowledgeExtractor`
- `GraphAssembler`
- `IndexingPipeline`

### Query Layer

Responsibilities:

- query normalization
- mode-specific retrieval
- context assembly
- response generation

Primary components:

- `QueryEngine`
- `LocalQueryStrategy`
- `GlobalQueryStrategy`
- `HybridQueryStrategy`
- `MixQueryStrategy`
- `ContextAssembler`

### Storage Layer

Responsibilities:

- document, chunk, graph, and vector persistence
- snapshot load and save
- internal lookup primitives for retrieval

The first version should ship with:

- `InMemoryDocumentStore`
- `InMemoryChunkStore`
- `InMemoryGraphStore`
- `InMemoryVectorStore`
- `FileSnapshotStore`

### Model Layer

Responsibilities:

- chat completion abstraction
- embedding abstraction
- prompt transport and response parsing

The first version should ship with:

- `OpenAiCompatibleChatModel`
- `OpenAiCompatibleEmbeddingModel`

## Package Layout

Recommended package layout:

```text
io.github.lightragjava
  api
  config
  exception
  indexing
  model
  persistence
  query
  retrieval
  storage
  types
```

This keeps the SDK small while leaving room for backend-specific packages later.

## Core Data Model

### Documents and Chunks

`Document`

- `id`
- `title`
- `content`
- `metadata`

`Chunk`

- `id`
- `documentId`
- `text`
- `tokenCount`
- `order`
- `metadata`

### Knowledge Graph

`Entity`

- `id`
- `name`
- `type`
- `description`
- `aliases`
- `sourceChunkIds`

`Relation`

- `id`
- `sourceEntityId`
- `targetEntityId`
- `type`
- `description`
- `weight`
- `sourceChunkIds`

### Query Types

`QueryRequest`

- `query`
- `mode`
- `topK`
- `chunkTopK`
- `responseType`

`QueryContext`

- `matchedEntities`
- `matchedRelations`
- `matchedChunks`
- `assembledContext`

`QueryResult`

- `answer`
- `contexts`
- `citations`
- `debugInfo`

## SPI Interfaces

The first version should define narrow interfaces for external dependencies and persistence.

### Model SPI

- `ChatModel`
- `EmbeddingModel`

### Storage SPI

- `DocumentStore`
- `ChunkStore`
- `GraphStore`
- `VectorStore`
- `SnapshotStore`

The first version should not model every storage concern from Python LightRAG. It should only expose interfaces necessary for the Java SDK core path.

## Indexing Flow

### Step 1: Ingest Documents

Accept one or more `Document` instances and validate required fields.

### Step 2: Chunk Documents

Split content into ordered chunks with overlap. The chunker should support token-based or character-window strategies, but a single default strategy is enough for v1.

### Step 3: Embed Chunks

Generate embeddings for each chunk and store them for retrieval.

### Step 4: Extract Entities and Relations

Use the chat model to extract structured entities and relations from each chunk.

The extraction contract should:

- require structured output
- validate malformed model responses
- keep raw extraction traces for debugging when enabled

### Step 5: Assemble and Merge Graph

Merge extracted entities and relations into the graph.

The first version should use simple merge rules:

- normalized name matching for entities
- explicit alias merge when present
- relation merge by normalized endpoints plus relation type

The first version should not attempt advanced probabilistic entity resolution.

### Step 6: Embed Entities and Relations

Create summary text for entities and relations, then embed them for graph-oriented retrieval.

### Step 7: Persist Snapshot

Write in-memory state to a file snapshot to support restart and reload.

## Query Modes

### Local

Focus on entity-centric retrieval:

- retrieve relevant entities
- expand neighboring relations
- gather supporting chunks

Best for focused questions about specific concepts, actors, or objects.

### Global

Focus on relation-centric or theme-centric retrieval:

- retrieve important relations or graph summaries
- aggregate supporting graph context
- gather representative chunks if needed

Best for summarization or cross-document questions.

### Hybrid

Combine local and global graph retrieval, then de-duplicate and trim context.

### Mix

Combine graph retrieval with chunk-level vector retrieval.

This should be the default mode for v1 because it is the most generally useful and resilient.

## Query Execution Flow

1. Validate `QueryRequest`.
2. Normalize the query text.
3. Execute the retrieval strategy for the selected mode.
4. Assemble context with token-budget-aware trimming.
5. Generate the answer using the chat model.
6. Return answer plus optional debugging metadata.

## Error Handling

Use a small domain-specific exception hierarchy:

- `ModelException`
- `StorageException`
- `ExtractionException`
- `QueryExecutionException`

Guidelines:

- wrap provider-specific or storage-specific exceptions before they leave the internal layer
- preserve root cause details for diagnostics
- keep public API errors stable and predictable

## Configuration

The SDK should prefer explicit builder-based configuration rather than implicit environment-heavy configuration.

Configuration categories:

- model settings
- chunking settings
- retrieval settings
- persistence settings
- debug settings

The first version should avoid excessive global mutable configuration.

## Testing Strategy

Minimum required test coverage for v1:

- chunking behavior
- graph merge behavior
- extraction response parsing
- in-memory storage contract tests
- each query mode behavior
- OpenAI-compatible adapter contract tests
- end-to-end ingest and query test

Testing priorities:

- correctness of data flow
- deterministic merge behavior
- stable retrieval semantics
- recoverable snapshot behavior

The first version does not need answer-quality benchmark coverage before the core pipeline is stable.

## Risks

### Extraction Fragility

LLM structured outputs may be malformed or inconsistent.

Mitigation:

- strict schema validation
- retry and repair hooks
- debug traces for failed extractions

### Over-Coupled Retrieval Logic

If query strategies directly depend on storage details, later backend support will be painful.

Mitigation:

- narrow storage interfaces
- keep retrieval logic storage-agnostic

### Premature Feature Expansion

Trying to match every Python LightRAG feature will slow delivery and destabilize the API.

Mitigation:

- keep v1 scope strict
- prioritize ingest, retrieval, and graph assembly correctness

## Delivery Plan

Suggested implementation phases:

1. Public API, models, and in-memory storage contracts
2. Chunking and chunk vector retrieval
3. Entity and relation extraction plus graph assembly
4. Query strategies: `local`, `global`, `hybrid`, `mix`
5. File snapshot persistence
6. OpenAI-compatible adapters hardening and end-to-end tests

## Future Extensions

Planned follow-up areas after v1:

- external graph and vector database adapters
- HTTP service wrapper
- reranking support
- incremental updates and deletes
- multi-workspace support
- richer entity resolution

## Final Recommendation

Build the Java implementation as a standalone repository with a layered SDK architecture and small SPI boundaries.

For v1, optimize for:

- a stable Java-first API
- in-memory execution with file snapshots
- a correct core LightRAG-style indexing and retrieval pipeline

Do not optimize v1 for:

- backend matrix breadth
- full Python compatibility
- operational platform features
