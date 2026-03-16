# lightrag-java

Standalone Java SDK for a LightRAG-style indexing and retrieval pipeline.

## Requirements

- A local JDK 17 is supported.
- If JDK 17 is not installed, Gradle is configured to auto-provision a matching toolchain.

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

## Spring Boot

The repository now includes two Spring-focused modules:

- `lightrag-core`: the framework-neutral SDK
- `lightrag-spring-boot-starter`: Spring Boot auto-configuration for `LightRag`
- `lightrag-spring-boot-demo`: a minimal REST demo application

The starter auto-configures `LightRag` from `application.yml` when you provide:

- chat model base URL, model name, and API key
- embedding model base URL, model name, and API key
- storage type: `in-memory`, `postgres`, or `postgres-neo4j`

The demo application exposes:

- `POST /documents/ingest`
- `POST /query`

Run the demo locally with:

```bash
./gradlew :lightrag-spring-boot-demo:bootRun
```

The demo's default config lives in:

- `lightrag-spring-boot-demo/src/main/resources/application.yml`

It defaults to `in-memory` storage and OpenAI-compatible model settings resolved from environment variables.

## Query Modes

The Java SDK currently supports six query modes:

- `NAIVE`: direct chunk-vector retrieval only; ignores graph expansion and uses `chunkTopK` as the effective retrieval limit
- `LOCAL`: entity-first graph expansion around locally similar entities
- `GLOBAL`: relation-first graph expansion around globally similar relations
- `HYBRID`: merged local + global graph retrieval
- `MIX`: hybrid graph retrieval plus direct chunk-vector retrieval
- `BYPASS`: skip retrieval and send the query directly to the configured chat model

Use `NAIVE` when you want the simplest upstream-aligned chunk search path or when your data quality favors direct vector similarity over graph structure.

## Rerank

The Java SDK supports an optional second-stage chunk reranker aligned with upstream LightRAG's rerank concept.

Configure it once on the builder:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .rerankModel(request -> request.candidates().stream()
        .sorted(java.util.Comparator.comparing(RerankModel.RerankCandidate::id).reversed())
        .map(candidate -> new RerankModel.RerankResult(candidate.id(), 1.0d))
        .toList())
    .storage(storage)
    .build();
```

Rerank is enabled by default on each query request and reorders the final chunk contexts before answer generation:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .chunkTopK(8)
    .build());
```

Disable it per request when needed:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .chunkTopK(8)
    .enableRerank(false)
    .build());
```

Notes:

- `NAIVE` also participates in rerank through the shared `QueryEngine`; rerank is not specific to graph-aware modes
- rerank is especially useful with `MIX` queries because the engine expands the internal candidate window before reranking
- rerank changes chunk order only; exposed context IDs/texts still come from the original retrieval records
- if `enableRerank(true)` is used without configuring a rerank model, Java treats it as a deterministic no-op in this phase

## Query Prompt Controls

The Java SDK supports the upstream query-time prompt controls `userPrompt` and `conversationHistory`.

It supports graph-retrieval keywords through `hlKeywords` and `llKeywords`, with automatic extraction when both are omitted in graph-aware modes.

It also supports upstream-style query token budgets through `maxEntityTokens`, `maxRelationTokens`, and `maxTotalTokens`.

It also supports upstream-style `includeReferences` for structured reference output.

It also supports upstream-style `stream` for incremental answer generation.

It also supports upstream-style query-time `modelFunc` overrides.

Use `userPrompt` when you want to add answer-formatting or style instructions without changing retrieval:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .userPrompt("Answer in bullet points.")
    .build());
```

Use `conversationHistory` when your chat model should see prior turns as structured messages:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .userPrompt("Answer in bullet points.")
    .conversationHistory(List.of(
        new ChatModel.ChatRequest.ConversationMessage("user", "We are discussing team structure."),
        new ChatModel.ChatRequest.ConversationMessage("assistant", "Understood.")
    ))
    .build());
```

Use `modelFunc` when one specific query should use a different chat model than the `LightRagBuilder` default:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .modelFunc(new OpenAiCompatibleChatModel(
        "https://api.openai.com/v1/",
        "gpt-4o",
        System.getenv("OPENAI_API_KEY")
    ))
    .build());
```

Use `llKeywords` to steer entity-oriented retrieval in `LOCAL`, `HYBRID`, and `MIX`, and `hlKeywords` to steer relation-oriented retrieval in `GLOBAL`, `HYBRID`, and `MIX`:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .mode(QueryMode.HYBRID)
    .llKeywords(List.of("Alice", "collaboration"))
    .hlKeywords(List.of("organization", "reporting"))
    .build());
```

If both keyword lists are omitted, Java now performs an upstream-style keyword-extraction pass for `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX` before retrieval. Manual keyword overrides always take precedence over automatic extraction.

Use token budgets when you need deterministic caps on how much graph and chunk context is retained:

```java
var result = rag.query(QueryRequest.builder()
    .query("Summarize the project status")
    .mode(QueryMode.MIX)
    .maxEntityTokens(6_000)
    .maxRelationTokens(8_000)
    .maxTotalTokens(30_000)
    .build());
```

Use `includeReferences` when you want structured references in `QueryResult` in addition to the generated answer:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .includeReferences(true)
    .build());

var references = result.references();
var firstContextReferenceId = result.contexts().get(0).referenceId();
var firstContextSource = result.contexts().get(0).source();
```

Use `stream` when you want to consume generated text incrementally while keeping retrieval metadata:

```java
try (var result = rag.query(QueryRequest.builder()
        .query("Who works with Bob?")
        .includeReferences(true)
        .stream(true)
        .build());
     var chunks = result.answerStream()) {
    while (chunks.hasNext()) {
        System.out.print(chunks.next());
    }
}
```

Notes:

- retrieval mode selection, graph expansion, and rerank behavior are unchanged by these fields
- `conversationHistory` is passed separately to the chat adapter; Java does not flatten those messages into the current-turn prompt
- `modelFunc(...)` overrides the builder-level `chatModel` for that query only
- `modelFunc(...)` applies to both buffered and streaming generation, including `BYPASS`
- when both lists are empty in `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`, Java automatically extracts high-level and low-level keywords before retrieval
- when either `hlKeywords` or `llKeywords` is provided, Java treats those lists as explicit overrides and skips automatic extraction
- `includeReferences(true)` adds `QueryResult.references()` plus `referenceId` / `source` on each returned `QueryResult.Context`
- `stream(true)` returns `QueryResult.streaming() == true`, leaves `QueryResult.answer()` empty, and exposes incremental model output through `QueryResult.answerStream()`
- `modelFunc(...)` affects query-time generation only; indexing and extraction still use the builder-configured `chatModel`
- streaming `QueryResult` implements `AutoCloseable`; close the result or its `answerStream()` when you stop reading early
- structured references are derived from the final chunk list after merge, rerank, and final token-budget trimming
- source resolution priority is `file_path`, then `source`, then `documentId`
- `maxEntityTokens` and `maxRelationTokens` cap formatted graph context rows in score order
- `maxTotalTokens` caps final chunk context after final merge/rerank ordering in `QueryEngine`
- defaults are `maxEntityTokens=6000`, `maxRelationTokens=8000`, and `maxTotalTokens=30000`
- chunk budgeting uses stored `Chunk.tokenCount()`, while prompt/query/entity/relation budgeting uses a shared lightweight text-token approximation in this phase
- recent query-request additions such as `stream` and `modelFunc` change the public `QueryRequest` record shape; builder-based callers remain source-compatible, but canonical-constructor or record-pattern consumers need updates
- in `HYBRID` and `MIX`, when manual keyword overrides are provided, only the non-empty keyword side participates in graph retrieval; direct chunk retrieval in `MIX` still uses the raw query text
- if automatic extraction returns no usable keywords, Java falls back to an upstream-like raw-query default by mode: `LOCAL`/`HYBRID`/`MIX` use low-level fallback, while `GLOBAL` uses high-level fallback
- in standard retrieval modes, Java now follows the upstream-style boundary more closely: retrieval instructions, `responseType`, `userPrompt`, and assembled context are sent through `systemPrompt`, while the current-turn user message is the raw query text
- standard retrieval modes now render richer upstream-style `---Role---`, `---Goal---`, `---Instructions---`, and `---Context---` sections instead of the earlier short custom template
- graph-aware modes mention both knowledge graph data and document chunks, while `NAIVE` uses document-chunk-only wording
- those standard retrieval templates also inherit upstream-style Markdown and same-language guidance, so `userPrompt` should be treated as additive instructions inside that scaffold rather than a complete replacement for it
- the default `responseType` is `Multiple Paragraphs`

## Query Shortcuts

The Java SDK also supports upstream-style query shortcuts for inspecting or skipping generation.

Use `onlyNeedContext` to return the assembled retrieval context without calling the chat model:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .onlyNeedContext(true)
    .build());

System.out.println(result.answer());   // assembled context text
System.out.println(result.contexts()); // resolved chunk contexts
```

Use `onlyNeedPrompt` to return the final prompt payload without calling the chat model:

```java
var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .onlyNeedPrompt(true)
    .build());

System.out.println(result.answer());   // rendered system prompt plus raw user query
```

Use `BYPASS` when you want a direct LLM call with optional chat history and prompt controls but no retrieval:

```java
var result = rag.query(QueryRequest.builder()
    .query("Talk directly to the model")
    .mode(QueryMode.BYPASS)
    .conversationHistory(List.of(
        new ChatModel.ChatRequest.ConversationMessage("user", "We are drafting a reply.")
    ))
    .userPrompt("Answer in one sentence.")
    .build());
```

Notes:

- in standard retrieval modes, `onlyNeedPrompt` takes precedence over `onlyNeedContext`
- `onlyNeedContext` returns assembled context text in `QueryResult.answer`
- `onlyNeedPrompt` returns an upstream-like prompt inspection payload: formatted system prompt plus a `---User Query---` section with the raw query text
- `onlyNeedContext(true)` and `onlyNeedPrompt(true)` bypass streaming and return buffered `QueryResult.answer()` payloads even if `stream(true)` is also set
- `onlyNeedContext(true)` and `onlyNeedPrompt(true)` also bypass `modelFunc(...)`; no chat model is invoked in those paths
- prompt inspection does not inline `conversationHistory`; those turns still travel separately in the real chat-model request
- plain `BYPASS` returns direct chat-model output in `QueryResult.answer` and an empty `contexts` list
- `BYPASS + stream(true)` streams directly from the chat model through `QueryResult.answerStream()` and still returns empty retrieval metadata
- `BYPASS + onlyNeedContext(true)` returns an empty `answer` and empty `contexts`
- `BYPASS + onlyNeedPrompt(true)` returns the bypass prompt payload in `QueryResult.answer`

## Document Status

The SDK exposes per-document processing status through typed APIs on `LightRag`:

```java
rag.ingest(List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

var status = rag.getDocumentStatus("doc-1");
var allStatuses = rag.listDocumentStatuses();
```

For the current synchronous ingest flow:

- a document becomes `PROCESSING` when its ingest attempt starts
- it becomes `PROCESSED` on success, with a short summary such as processed chunk count
- it becomes `FAILED` on ingest failure, with the top-level error message
- `deleteByDocumentId(...)` removes the persisted status entry when deletion succeeds

There is no background queue in this phase, so status visibility is per synchronous document ingest attempt rather than async job orchestration.

## Graph Management

The Java SDK now supports manual graph mutations in addition to document ingest:

- `createEntity(CreateEntityRequest request)`
- `editEntity(EditEntityRequest request)`
- `createRelation(CreateRelationRequest request)`
- `editRelation(EditRelationRequest request)`

```java
var alice = rag.createEntity(CreateEntityRequest.builder()
    .name("Alice")
    .type("person")
    .description("Researcher")
    .aliases(List.of("Dr. Alice"))
    .build());

var bob = rag.createEntity(CreateEntityRequest.builder()
    .name("Bob")
    .type("person")
    .description("Engineer")
    .build());

var worksWith = rag.createRelation(CreateRelationRequest.builder()
    .sourceEntityName("Alice")
    .targetEntityName("Bob")
    .relationType("works_with")
    .description("Cross-team collaboration")
    .weight(0.8d)
    .build());

var robert = rag.editEntity(EditEntityRequest.builder()
    .entityName("Bob")
    .newName("Robert")
    .description("Principal investigator")
    .build());

var reportsTo = rag.editRelation(EditRelationRequest.builder()
    .sourceEntityName("Alice")
    .targetEntityName("Robert")
    .currentRelationType("works_with")
    .newRelationType("reports_to")
    .description("Formal reporting line")
    .weight(0.9d)
    .build());

var merged = rag.mergeEntities(MergeEntitiesRequest.builder()
    .sourceEntityNames(List.of("Bob"))
    .targetEntityName("Robert")
    .targetDescription("Principal investigator leading the merged profile")
    .targetAliases(List.of("Rob"))
    .build());
```

Notes:
- Entity lookup is deterministic: exact normalized names win, aliases are only used when they resolve to exactly one entity.
- Entity names and aliases share one external lookup namespace, so a new name or alias cannot reuse another entity's name or alias.
- Java relation operations require an explicit relation type because relation identity is `sourceEntityId + normalizedRelationType + targetEntityId`.
- `mergeEntities(...)` merges existing source entities into an existing target entity, redirects source relations, folds duplicate rewritten relations, and drops self-loops created by the merge.

## PostgreSQL Storage

`PostgresStorageProvider` stores documents, chunks, graph records, vectors, and document processing statuses in PostgreSQL so the SDK can survive process restarts without relying on JSON snapshots as the primary data store.

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

`PostgresStorageProvider` bootstraps its schema automatically on startup. Ingest writes run atomically across document, chunk, graph, vector, and document-status storage.

## PostgreSQL + Neo4j Storage

`PostgresNeo4jStorageProvider` keeps PostgreSQL as the durable source of truth for documents, chunks, graph rows, vectors, and document statuses, while projecting graph reads into Neo4j.

### Prerequisites

- PostgreSQL 16+ with the `vector` extension available
- Neo4j 5+ with Bolt enabled
- Database users that can create the required PostgreSQL tables and Neo4j constraints
- A configured `SnapshotStore` for `loadFromSnapshot(...)` and autosave flows

For local development and Testcontainers, the expected images are:

- `pgvector/pgvector:pg16`
- `neo4j:5-community`

### Quick Start

```java
var storage = new PostgresNeo4jStorageProvider(
    new PostgresStorageConfig(
        "jdbc:postgresql://localhost:5432/lightrag",
        "postgres",
        "postgres",
        "public",
        1536,
        "rag_"
    ),
    new Neo4jGraphConfig(
        "bolt://localhost:7687",
        "neo4j",
        "password",
        "neo4j"
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
```

This mixed provider uses a compensation-based rollback model: PostgreSQL remains the source of truth, Neo4j serves graph reads, and failed graph projection updates are rolled back to the pre-write snapshot so the SDK still presents provider-level atomic outcomes.

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
- If the snapshot file already exists, it restores documents, chunks, graph data, vectors, and document statuses before `build()`.
- It also sets the autosave target used after successful `ingest(...)` calls.

With the PostgreSQL and PostgreSQL+Neo4j backends, snapshots remain delegated to the configured `SnapshotStore`. PostgreSQL is the primary durable store for online data and document statuses, while snapshot files are still used only when you explicitly load or autosave snapshots through the existing API.

## Current v1 Scope

- Bundled storage providers: in-memory, PostgreSQL, and PostgreSQL+Neo4j.
- PostgreSQL is the current durable source of truth for documents, chunks, graph data, vectors, and document statuses.
- `PostgresNeo4jStorageProvider` adds Neo4j-backed graph reads on top of PostgreSQL durability.
- Manual graph-management APIs support create/edit flows for entities and relations across all bundled providers.
- Document-status APIs support querying per-document `PROCESSING`, `PROCESSED`, and `FAILED` outcomes.
- Snapshot persistence still uses the `SnapshotStore` SPI and remains file-based by default.
- Query modes supported today: `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`.
- Optional builder-level rerank support can reorder retrieved chunk contexts before answer generation.
- Extraction and graph merge rules are intentionally simple and deterministic.
- OpenAI-compatible adapters support standard `/chat/completions` and `/embeddings` endpoints.
- A pure Neo4j-only storage provider is not bundled in this phase.
