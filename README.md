# lightrag-java

![Java 17](https://img.shields.io/badge/Java-17-437291)
![Gradle 8](https://img.shields.io/badge/Gradle-8.14.3-02303A)
![Spring Boot Starter](https://img.shields.io/badge/Spring_Boot-Starter-6DB33F)
![RAGAS Eval](https://img.shields.io/badge/Evaluation-RAGAS-7B61FF)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dargoner/lightrag-core)](https://search.maven.org/artifact/io.github.dargoner/lightrag-core)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)

Standalone Java SDK for a LightRAG-style indexing and retrieval pipeline.

## Requirements

- A local JDK 17 is supported.
- If JDK 17 is not installed, Gradle is configured to auto-provision a matching toolchain.

## Publishing

Published artifacts use the `io.github.dargoner` group:

- `io.github.dargoner:lightrag-core`
- `io.github.dargoner:lightrag-spring-boot-starter`

The `lightrag-spring-boot-demo` module is for local demo usage and is not a Maven Central artifact.

Releases are published by the GitHub Actions `Release` workflow.

Standard release:

```bash
git tag v0.3.0
git push origin v0.3.0
```

Patch or security-fix release:

```bash
git switch -c hotfix/0.2.x v0.2.0
# apply the fix
git commit -am "fix: security patch"
git push origin hotfix/0.2.x

git tag v0.2.1
git push origin v0.2.1
```

Patch/security releases from a hotfix branch do not change `main`. Example: releasing `v0.2.1` keeps `main` on `0.3.0-SNAPSHOT`.

You can also run the `Release` workflow manually from GitHub Actions and set `release_version`, but pushing the release tag is the normal path.

After a successful release from `main`, the repository default version is advanced automatically to the next minor snapshot. Example: `v0.2.0` releases `0.2.0`, then `main` moves to `0.3.0-SNAPSHOT`.

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

rag.ingest("default", List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .build());

System.out.println(result.answer());
```

For tests, demos, and ephemeral runs, the in-memory provider is still the fastest option. For restart-safe ingestion and durable local state, use the PostgreSQL provider described below.

All runtime data operations on `LightRag` now require an explicit `workspaceId`. The SDK no longer keeps ambiguous zero-argument runtime aliases for ingest, query, graph management, deletion, document status, or snapshot APIs.

## Async Tasks & Monitoring

The SDK now includes a native async task runtime for long-running ingest and rebuild work.

Use the async APIs when you want durable task records, stage-level progress, cancellation, and polling without building your own job layer:

- `submitIngest(workspaceId, documents)`
- `submitIngestSources(workspaceId, sources, options)`
- `submitRebuild(workspaceId)`
- `getTask(workspaceId, taskId)`
- `listTasks(workspaceId)`
- `cancelTask(workspaceId, taskId)`

Example:

```java
var taskId = rag.submitIngest("default", List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

TaskSnapshot task = rag.getTask("default", taskId);

if (!task.status().isTerminal()) {
    System.out.println("Current stage: " + task.stages());
}
```

Task snapshots include:

- task type and lifecycle status
- requested / started / finished timestamps
- summary, error message, and cancel flag
- stage snapshots such as `PARSING`, `CHUNKING`, `PRIMARY_EXTRACTION`, `GRAPH_ASSEMBLY`, `VECTOR_INDEXING`, `COMMITTING`, and `COMPLETED`

Current storage support for task persistence and stage monitoring:

- `InMemoryStorageProvider`
- `PostgresStorageProvider`
- `PostgresNeo4jStorageProvider`
- `PostgresMilvusNeo4jStorageProvider`
- `MySqlMilvusNeo4jStorageProvider`

## Multi-Workspace Runtime

When one `LightRag` instance needs to serve multiple workspaces, build it through `workspaceStorage(...)`:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .workspaceStorage(new WorkspaceStorageProvider() {
        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            return InMemoryStorageProvider.create();
        }

        @Override
        public void close() {
        }
    })
    .build();

rag.ingest("workspace-a", documents);
var result = rag.query("workspace-b", QueryRequest.builder()
    .query("What changed?")
    .build());
```

Rules in this model:

- every ingest/query/graph/delete/status/snapshot call must pass `workspaceId`
- the same `LightRag` instance can serve multiple workspaces
- storage isolation stays behind `WorkspaceStorageProvider`; `workspaceId` is not added to the public store SPI
- `loadFromSnapshot(...)` remains a legacy single-workspace builder feature and is blocked for `workspaceStorage(...)`

## Spring Boot

The repository now includes two Spring-focused modules:

- `lightrag-core`: the framework-neutral SDK
- `lightrag-spring-boot-starter`: Spring Boot auto-configuration for `LightRag`
- `lightrag-spring-boot-demo`: a minimal REST demo application

The starter auto-configures `LightRag` from `application.yml` when you provide:

- chat model base URL, model name, and API key
- embedding model base URL, model name, and API key
- optional chat and embedding request timeouts
- storage type: `in-memory`, `postgres`, or `postgres-neo4j`
- indexing defaults for chunk window and overlap
- optional indexing embedding batch size
- query defaults for automatic keyword extraction and rerank candidate expansion
- demo defaults for query mode, top-k, response type, and async ingest behavior

The demo application exposes:

- `POST /documents/ingest`: submit an ingest job and receive a `jobId`
- `POST /documents/upload`: upload one or more text files and receive a `jobId` plus generated `documentIds`
- `GET /documents/jobs?page=0&size=20`: list recent ingest jobs with pagination
- `GET /documents/jobs/{jobId}`: poll async ingest state
- `POST /documents/jobs/{jobId}/cancel`: cancel a pending job or request cancellation for a running job
- `POST /documents/jobs/{jobId}/retry`: create a new attempt from a `FAILED` or `CANCELLED` job
- `GET /documents/status`
- `GET /documents/status/{documentId}`
- `DELETE /documents/{documentId}`
- `POST /query`
- `POST /query/stream`
- `POST /graph/entities`
- `PUT /graph/entities`
- `POST /graph/entities/merge`
- `DELETE /graph/entities/{entityName}`
- `POST /graph/relations`
- `PUT /graph/relations`
- `DELETE /graph/relations?sourceEntityName=...&targetEntityName=...`
- `GET /actuator/health`
- `GET /actuator/info`

All demo endpoints resolve a workspace before touching storage. By default the demo reads `X-Workspace-Id`; when the header is omitted it falls back to the configured default workspace.

Run the demo locally with:

```bash
./gradlew :lightrag-spring-boot-demo:bootRun
```

The demo's default config lives in:

- `lightrag-spring-boot-demo/src/main/resources/application.yml`

It defaults to `in-memory` storage, OpenAI-compatible model settings resolved from environment variables, buffered `/query` responses, SSE `/query/stream` responses, and async ingest enabled.

When you need stricter network control, the starter also exposes OpenAI-compatible timeout settings:

```yaml
lightrag:
  chat:
    timeout: PT45S
  embedding:
    timeout: PT15S
```

Both values use ISO-8601 `Duration` syntax and default to `PT30S`.

## Chunking

The SDK can now override the ingest-time `Chunker` instead of always using the built-in fixed-window split.

Use a custom chunker from the builder when you need application-specific chunk boundaries:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(document -> List.of(
        new Chunk(document.id() + ":0", document.id(), document.content(), document.content().length(), 0, document.metadata())
    ))
    .build();
```

For structure-aware chunking, use `SmartChunker` directly through the same builder hook:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(800)
        .maxTokens(1200)
        .overlapTokens(100)
        .semanticMergeEnabled(true)
        .semanticMergeThreshold(0.80d)
        .build()))
    .build();
```

`SmartChunker` currently adds three layers on top of the existing `Chunker` SPI:

- V1: sentence-aware splitting and overlap while keeping the current `Chunk` contract
- V2: markdown-like heading, list, and table preservation with string metadata such as `smart_chunker.section_path`
- V3: optional adjacent-chunk semantic merge plus a reflection-based `LangChain4jChunkAdapter`

Starting with this iteration, `SmartChunker` also enables **adaptive paragraph chunking** internally by default:

- users still choose only `FINE / MEDIUM / COARSE`
- long continuous prose is allowed to become coarser within that granularity band
- adjacent short prose paragraphs in the same section can be regrouped before final sentence-aware splitting
- the first paragraph after a heading is biased finer for retrieval precision
- very short page chrome or short non-substantive snippets are intentionally kept conservative rather than blindly regrouped
- `LIST` and `TABLE` stay conservative rather than aggressively adapting
- `LAW` and `QA` only allow very small dynamic adjustment so template boundaries remain stable

The standalone `SmartChunker.chunk(...)` path remains model-free. Its V3 merge still uses the local heuristic scorer from `SmartChunkerConfig` and does not require an `EmbeddingModel`.

If you want embedding-driven semantic merge, enable it on the `LightRag` ingest pipeline instead of on the standalone chunker API:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(800)
        .maxTokens(1200)
        .overlapTokens(100)
        .build()))
    .enableEmbeddingSemanticMerge(true)
    .embeddingSemanticMergeThreshold(0.80d)
    .build();
```

That ingest-only path starts from `SmartChunker.chunkStructural(...)`, computes embeddings with the configured `EmbeddingModel`, and only then merges adjacent chunks when section and content-type boundaries allow it. Query-time chunking and standalone `SmartChunker.chunk(...)` behavior stay unchanged.

The additional metadata is still stored as `Map<String, String>`, so it remains compatible with the current query, snapshot, and PostgreSQL persistence pipeline.

If you need LangChain4j-compatible segments, adapt generated chunks explicitly:

```java
var chunker = new SmartChunker(SmartChunkerConfig.defaults());
var chunks = chunker.chunk(new Document("doc-1", "Guide", "# Policies\nCarry your passport.", Map.of()));
var adapter = new LangChain4jChunkAdapter();
var segments = adapter.toTextSegments(chunks);
```

If you want the SDK to ingest files directly, use the raw-source entry instead of converting everything to `Document` yourself:

```java
var source = RawDocumentSource.bytes(
    "contract.docx",
    Files.readAllBytes(Path.of("contract.docx")),
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
);

rag.ingestSources(
    List.of(source),
    new DocumentIngestOptions(DocumentTypeHint.LAW, ChunkGranularity.MEDIUM)
);
```

Raw-source ingest keeps parser backend selection internal:

- `.txt`, `.md`, `.markdown`: direct UTF-8 plain-text parsing
- `.pdf`, `.doc`, `.docx`, `.ppt`, `.pptx`, `.html`, `.htm`: `MinerU` first, then `Tika` fallback when enabled
- `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.bmp`: `MinerU` only; there is no OCR fallback outside MinerU
- images and figures inside documents are first converted by `MinerU` into OCR text, captions, or layout text blocks before `SmartChunker` runs; pure visual content does not currently get its own image embedding path

For the hosted `mineru.net` API mode, the source must also provide a public file URL in metadata such as `sourceUrl`.
The hosted API does not accept local file bytes directly, so raw upload bytes still need either:

- a self-hosted MinerU deployment, or
- a staging/object-storage step that turns the upload into a reachable URL before parsing

Chunk selection also stays business-oriented:

- default `AUTO`: use regex/manual chunking when regex rules are supplied, otherwise use `SmartChunker`
- force `SMART`, `REGEX`, or `FIXED` through `DocumentIngestOptions`
- enable optional parent/child chunks when you want retrieval to recall child hits and expand them back to parent context

With Spring Boot Starter, fixed-window chunking can be configured optionally in `application.yml`. If omitted, it still defaults to `window-size=1000` and `overlap=100`:

```yaml
lightrag:
  indexing:
    chunking:
      window-size: 1200
      overlap: 150
    ingest:
      preset: GENERAL
      parent-child-window-size: 400
      parent-child-overlap: 40
    parsing:
      tika-fallback-enabled: true
      mineru:
        enabled: false
        mode: DISABLED
        base-url: http://127.0.0.1:8000
        api-key: ${MINERU_API_KEY:}
    embedding-batch-size: 32
    max-parallel-insert: 4
    entity-extract-max-gleaning: 1
    max-extract-input-tokens: 20480
    language: Chinese
    entity-types: Person,Organization
```

`ingest.preset` is the primary product-facing option. Supported values are `GENERAL`, `LAW`, `BOOK`, `QA`, and `FIGURE`.

The legacy properties below are still supported for backward compatibility:

- `lightrag.indexing.ingest.document-type`
- `lightrag.indexing.ingest.chunk-granularity`
- `lightrag.indexing.ingest.parent-child-enabled`

If no request-level `preset` override is provided, those legacy properties still override the preset-derived defaults.

`embedding-batch-size` controls how many texts are sent in each indexing-time embedding request. Leave it unset or `0` to preserve the current single-batch behavior.
`max-parallel-insert` controls how many documents ingest can process concurrently. It defaults to `1` so existing runtime behavior stays serial unless you opt in.
`entity-extract-max-gleaning` controls how many follow-up extraction passes run for the same chunk after the first LLM extraction.
`max-extract-input-tokens` caps the estimated extraction context budget before a glean pass is skipped.
`language` controls the language used in entity descriptions and extraction guidance. It defaults to `English`.
`entity-types` narrows or extends the preferred extraction taxonomy. The default list is `Person, Creature, Organization, Location, Event, Concept, Method, Content, Data, Artifact, NaturalObject, Other`.
When `max-parallel-insert` is greater than `1`, custom `Chunker`, `ChatModel`, and `EmbeddingModel` implementations must be safe for concurrent use.

If the application provides its own `Chunker` bean, the starter backs off and uses that bean instead.

For lightweight operations visibility, the demo also exposes:

- `/actuator/health`: application health plus a `lightrag` component with storage type and async ingest flags
- `/actuator/info`: static runtime info such as storage type, async ingest setting, and default query mode

Use `POST /documents/ingest` when you already have structured `Document` JSON payloads. Use `POST /documents/upload` when you want the demo to pass raw file bytes into the SDK and let the parsing pipeline choose `plain -> MinerU -> Tika` automatically.

The upload endpoint accepts `multipart/form-data` with one or more `files` parts, an optional `async=true|false` query parameter, and an optional `preset=GENERAL|LAW|BOOK|QA|FIGURE` override. Supported file types are:

- `.txt`
- `.md`
- `.markdown`
- `.pdf`
- `.doc`
- `.docx`
- `.ppt`
- `.pptx`
- `.html`
- `.htm`
- `.png`
- `.jpg`
- `.jpeg`
- `.webp`
- `.gif`
- `.bmp`

Current demo limits:

- maximum `20` files per request
- maximum `1 MiB` per file
- maximum `4 MiB` total request payload across all files
- `.txt` / `.md` / `.markdown` content must decode as valid UTF-8
- binary office, pdf, html, and image uploads keep raw bytes and are parsed later during ingest

Parsing behavior for upload jobs:

- office, pdf, and html files prefer `MinerU`; if `MinerU` is unavailable, the pipeline downgrades to `Tika` when fallback is enabled
- image files require `MinerU`; the job fails instead of pretending OCR succeeded with empty text
- chunking still follows `DocumentIngestOptions`, so you can keep the default smart strategy or switch to regex/manual chunking in SDK integrations

Each uploaded file becomes a `RawDocumentSource` with:

- a generated URL-safe `sourceId`
- the original filename and media type
- metadata including `source=upload`, `fileName`, and `contentType`

Workspace routing is configured through the starter properties:

- `lightrag.workspace.header-name`: request header used by the demo, default `X-Workspace-Id`
- `lightrag.workspace.default-id`: fallback workspace when the header is missing, default `default`
- `lightrag.workspace.max-active-workspaces`: upper bound for cached workspace instances, default `32`

Upload example:

```bash
curl -X POST http://127.0.0.1:8080/documents/upload \
  -H 'X-Workspace-Id: team-a' \
  -F 'files=@notes.md;type=text/markdown' \
  -F 'files=@facts.txt;type=text/plain'
```

The response returns a `jobId` and `documentIds`. Use those IDs with `/documents/status/{documentId}` or `DELETE /documents/{documentId}` after the ingest job completes.

The job endpoints expose lightweight observability fields for demo troubleshooting:

- `documentCount`: number of submitted documents in the job
- `createdAt`, `startedAt`, `finishedAt`: basic ingest timeline
- `errorMessage`: populated when the job reaches `FAILED`
- `cancellable`, `retriable`, `retriedFromJobId`, `attempt`: basic job lifecycle controls

Workspace-scoped structured ingest example:

```bash
curl -X POST http://127.0.0.1:8080/documents/ingest \
  -H 'Content-Type: application/json' \
  -H 'X-Workspace-Id: team-a' \
  -d '{
    "documents": [
      {
        "id": "doc-1",
        "title": "Title",
        "content": "Alice works with Bob"
      }
    ]
  }'
```

Workspace isolation support in this phase:

- `in-memory`: each workspace gets an isolated in-process `LightRag` instance
- `postgres`: all workspaces share one table set per configured prefix and isolate rows by `workspace_id`; snapshot paths are still derived per workspace
- `postgres-neo4j`: current behavior is preserved for the default workspace only; non-default workspaces are not supported yet
- custom `StorageProvider` beans remain default-workspace only unless you provide your own workspace-aware routing layer

Cancel/retry semantics in this phase:

- cancelling a `PENDING` job moves it directly to `CANCELLED`
- cancelling a `RUNNING` job is best-effort and temporarily reports `CANCELLING`
- retry skips documents that are already `PROCESSED`, so partial-success batches do not immediately fail on duplicate ids

The demo `/query` and `/query/stream` endpoints accept the core query controls used most often in service mode, including:

- `mode`, `topK`, `chunkTopK`
- `maxEntityTokens`, `maxRelationTokens`, `maxTotalTokens`
- `maxHop`, `pathTopK`, `multiHopEnabled`
- `responseType`, `enableRerank`
- `includeReferences`, `onlyNeedContext`, `onlyNeedPrompt`
- `userPrompt`, `hlKeywords`, `llKeywords`, `conversationHistory`

Use `POST /query` for buffered JSON responses. `stream=true` is intentionally rejected there to keep the contract unambiguous.

Use `POST /query/stream` with `Accept: text/event-stream` for SSE output. The stream protocol emits:

- `meta`: always first; includes `streaming`, `contexts`, and `references`
- `chunk`: incremental answer text when core streaming is active
- `answer`: single buffered payload when `onlyNeedContext(true)` or `onlyNeedPrompt(true)` bypasses streaming
- `complete`: terminal success marker
- `error`: terminal failure marker when streaming work aborts after the response starts

`/query/stream` always maps to `QueryRequest.stream(true)`. The core still applies its existing shortcut semantics, so `onlyNeedContext(true)` and `onlyNeedPrompt(true)` return `meta + answer + complete` instead of chunked output. `BYPASS` mode still streams chunk events, with empty retrieval metadata.

## Query Modes

The Java SDK currently supports six query modes:

- `NAIVE`: direct chunk-vector retrieval only; ignores graph expansion and uses `chunkTopK` as the effective retrieval limit
- `LOCAL`: entity-first graph expansion around locally similar entities
- `GLOBAL`: relation-first graph expansion around globally similar relations
- `HYBRID`: merged local + global graph retrieval
- `MIX`: hybrid graph retrieval plus direct chunk-vector retrieval
- `BYPASS`: skip retrieval and send the query directly to the configured chat model

Use `NAIVE` when you want the simplest upstream-aligned chunk search path or when your data quality favors direct vector similarity over graph structure.

## Multi-Hop Querying

Graph-aware queries can now opt into a lightweight multi-hop reasoning path over the existing entity-relation graph.

Use the per-request controls when you need the engine to search for short relation chains instead of answering from a single local fact:

```java
var result = rag.query("default", QueryRequest.builder()
    .query("Atlas 通过谁关联 KnowledgeGraphTeam？")
    .mode(QueryMode.LOCAL)
    .maxHop(2)
    .pathTopK(3)
    .multiHopEnabled(true)
    .build());
```

The current implementation is intentionally narrow:

- multi-hop is only considered when `multiHopEnabled(true)` is set; it defaults to `true`
- `NAIVE` and `BYPASS` never use multi-hop expansion
- the intent classifier currently treats queries containing phrases such as `通过`, `经过`, `间接`, `多跳`, `先...再...`, `through`, `via`, `indirect`, or `first ... then ...` as multi-hop candidates
- seed retrieval still starts from the normal graph-aware retrieval stack, then expands paths through `GraphStore.findRelations(...)`
- `maxHop` defaults to `2`
- `pathTopK` defaults to `3`

When a multi-hop path is selected, the assembled context includes structured sections such as:

- `Reasoning Path 1`
- `Hop 1: A -> B | keywords: relation`
- `Relation detail: ...`
- `Evidence [chunk-id]: ...`

That structure is preserved in `onlyNeedContext(true)` and `onlyNeedPrompt(true)` responses, and the standard answer path injects extra instructions so the chat model explains the answer hop by hop instead of collapsing unsupported intermediate steps.

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
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .chunkTopK(8)
    .build());
```

Disable it per request when needed:

```java
var result = rag.query("default", QueryRequest.builder()
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

It also supports multi-hop path controls through `maxHop`, `pathTopK`, and `multiHopEnabled`.

It also supports upstream-style `includeReferences` for structured reference output.

It also supports upstream-style `stream` for incremental answer generation.

It also supports upstream-style query-time `modelFunc` overrides.

Use `userPrompt` when you want to add answer-formatting or style instructions without changing retrieval:

```java
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .userPrompt("Answer in bullet points.")
    .build());
```

Use `conversationHistory` when your chat model should see prior turns as structured messages:

```java
var result = rag.query("default", QueryRequest.builder()
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
var result = rag.query("default", QueryRequest.builder()
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
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .mode(QueryMode.HYBRID)
    .llKeywords(List.of("Alice", "collaboration"))
    .hlKeywords(List.of("organization", "reporting"))
    .build());
```

If both keyword lists are omitted, Java now performs an upstream-style keyword-extraction pass for `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX` before retrieval. Manual keyword overrides always take precedence over automatic extraction.

Use token budgets when you need deterministic caps on how much graph and chunk context is retained:

```java
var result = rag.query("default", QueryRequest.builder()
    .query("Summarize the project status")
    .mode(QueryMode.MIX)
    .maxEntityTokens(6_000)
    .maxRelationTokens(8_000)
    .maxTotalTokens(30_000)
    .build());
```

Use `includeReferences` when you want structured references in `QueryResult` in addition to the generated answer:

```java
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .includeReferences(true)
    .build());

var references = result.references();
var firstContextReferenceId = result.contexts().get(0).referenceId();
var firstContextSource = result.contexts().get(0).source();
```

Use `stream` when you want to consume generated text incrementally while keeping retrieval metadata:

```java
try (var result = rag.query("default", QueryRequest.builder()
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
- `maxHop` limits the maximum reasoning-path depth when multi-hop is active
- `pathTopK` limits how many reranked reasoning paths are retained in the assembled context
- `multiHopEnabled(false)` forces graph-aware modes back to their normal single-hop retrieval behavior
- defaults are `maxEntityTokens=6000`, `maxRelationTokens=8000`, and `maxTotalTokens=30000`
- defaults are also `maxHop=2`, `pathTopK=3`, and `multiHopEnabled=true`
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
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .onlyNeedContext(true)
    .build());

System.out.println(result.answer());   // assembled context text
System.out.println(result.contexts()); // resolved chunk contexts
```

Use `onlyNeedPrompt` to return the final prompt payload without calling the chat model:

```java
var result = rag.query("default", QueryRequest.builder()
    .query("Who works with Bob?")
    .onlyNeedPrompt(true)
    .build());

System.out.println(result.answer());   // rendered system prompt plus raw user query
```

Use `BYPASS` when you want a direct LLM call with optional chat history and prompt controls but no retrieval:

```java
var result = rag.query("default", QueryRequest.builder()
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
rag.ingest("default", List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

var status = rag.getDocumentStatus("default", "doc-1");
var allStatuses = rag.listDocumentStatuses("default");
```

For the current synchronous ingest flow:

- a document becomes `PROCESSING` when its ingest attempt starts
- it becomes `PROCESSED` on success, with a short summary such as processed chunk count
- it becomes `FAILED` on ingest failure, with the top-level error message
- `deleteByDocumentId(workspaceId, ...)` removes the persisted status entry when deletion succeeds

There is no background queue in this phase, so status visibility is per synchronous document ingest attempt rather than async job orchestration.

## Graph Management

The Java SDK now supports manual graph mutations in addition to document ingest:

- `createEntity(String workspaceId, CreateEntityRequest request)`
- `editEntity(String workspaceId, EditEntityRequest request)`
- `createRelation(String workspaceId, CreateRelationRequest request)`
- `updateRelation(String workspaceId, UpdateRelationRequest request)`
- `deleteRelation(String workspaceId, DeleteRelationRequest request)`

```java
var alice = rag.createEntity("default", CreateEntityRequest.builder()
    .name("Alice")
    .type("person")
    .description("Researcher")
    .aliases(List.of("Dr. Alice"))
    .build());

var bob = rag.createEntity("default", CreateEntityRequest.builder()
    .name("Bob")
    .type("person")
    .description("Engineer")
    .build());

var worksWith = rag.createRelation("default", CreateRelationRequest.builder()
    .sourceEntityName("Alice")
    .targetEntityName("Bob")
    .keywords("works_with")
    .description("Cross-team collaboration")
    .weight(0.8d)
    .build());

var robert = rag.editEntity("default", EditEntityRequest.builder()
    .entityName("Bob")
    .newName("Robert")
    .description("Principal investigator")
    .build());

var reportsTo = rag.updateRelation("default", UpdateRelationRequest.builder()
    .sourceEntityName("Alice")
    .targetEntityName("Robert")
    .keywords("reports_to")
    .description("Formal reporting line")
    .weight(0.9d)
    .build());

var merged = rag.mergeEntities("default", MergeEntitiesRequest.builder()
    .sourceEntityNames(List.of("Bob"))
    .targetEntityName("Robert")
    .targetDescription("Principal investigator leading the merged profile")
    .targetAliases(List.of("Rob"))
    .build());
```

Notes:
- Entity lookup is deterministic: exact normalized names win, aliases are only used when they resolve to exactly one entity.
- Entity names and aliases share one external lookup namespace, so a new name or alias cannot reuse another entity's name or alias.
- Java relation operations align with upstream LightRAG: relation identity is the canonical endpoint pair, while relation semantics live in `keywords`.
- Relation IDs use the upstream-style short format `rel-<md5(canonical_src + canonical_tgt)>`.
- `mergeEntities(...)` merges existing source entities into an existing target entity, redirects source relations onto canonical endpoint pairs, folds duplicate rewritten relations, and drops self-loops created by the merge.

## PostgreSQL Storage

`PostgresStorageProvider` stores documents, chunks, graph records, vectors, document processing statuses, and task metadata in PostgreSQL so the SDK can survive process restarts without relying on JSON snapshots as the primary data store.

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

rag.ingest("default", List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "postgres-demo"))
));
```

`PostgresStorageProvider` bootstraps its schema automatically on startup. Ingest writes run atomically across document, chunk, graph, vector, and document-status storage. Async SDK tasks are persisted in PostgreSQL task tables and can be polled after process restart.

## PostgreSQL + Neo4j Storage

`PostgresNeo4jStorageProvider` keeps PostgreSQL as the durable source of truth for documents, chunks, graph rows, vectors, document statuses, and task metadata, while projecting graph reads into Neo4j.

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

Isolation strategy in the mixed backend is intentionally asymmetric:

- PostgreSQL uses one shared table set and isolates rows by `workspace_id`
- Neo4j uses one shared database and isolates records logically by `workspaceId`
- Neo4j snapshot capture, restore, and projection rollback are all scoped to the current workspace only

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

rag.ingest("default", documents);
rag.saveSnapshot("default", snapshotPath);
rag.restoreSnapshot("default", snapshotPath);
```

`loadFromSnapshot(...)` does two things:
- If the snapshot file already exists, it restores documents, chunks, graph data, vectors, and document statuses before `build()`.
- It also sets the autosave target used after successful `ingest(...)` calls.

Runtime snapshots are now workspace-explicit:

- `saveSnapshot(workspaceId, path)` captures only that workspace
- `restoreSnapshot(workspaceId, path)` restores only that workspace

With the PostgreSQL and PostgreSQL+Neo4j backends, snapshots remain delegated to the configured `SnapshotStore`. PostgreSQL is the primary durable store for online data and document statuses, while snapshot files are still used only when you explicitly load or autosave snapshots through the existing API.

## Indexing and Retrieval Tuning

The builder now exposes a small set of indexing and retrieval tuning controls without changing the public query request contract:

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(new FixedWindowChunker(600, 80))
    .embeddingBatchSize(32)
    .maxParallelInsert(4)
    .entityExtractMaxGleaning(1)
    .maxExtractInputTokens(20_480)
    .entityExtractionLanguage("Chinese")
    .entityTypes(List.of("Person", "Organization"))
    .automaticQueryKeywordExtraction(false)
    .rerankCandidateMultiplier(4)
    .build();
```

- `chunker(...)`: replaces the default fixed-window chunker used during ingest
- `embeddingBatchSize(...)`: caps the number of texts per embedding request during indexing
- `maxParallelInsert(...)`: caps how many documents `ingest(...)` processes concurrently
- `entityExtractMaxGleaning(...)`: controls how many follow-up extraction passes run per chunk
- `maxExtractInputTokens(...)`: caps estimated extraction context before gleaning is skipped
- `entityExtractionLanguage(...)`: changes the language used in extraction-time guidance and generated descriptions
- `entityTypes(...)`: overrides the preferred entity taxonomy used in extraction prompts
- `automaticQueryKeywordExtraction(...)`: turns graph-mode keyword extraction on or off
- `rerankCandidateMultiplier(...)`: controls how far `QueryEngine` expands `chunkTopK` before reranking

Defaults in this phase:

- chunker: `FixedWindowChunker(1000, 100)`
- embedding batch size: unbounded single batch
- max parallel insert: `1`
- entity extract max gleaning: `1`
- max extract input tokens: `20480`
- entity extraction language: `English`
- entity types: `Person, Creature, Organization, Location, Event, Concept, Method, Content, Data, Artifact, NaturalObject, Other`
- automatic keyword extraction: `true`
- rerank candidate multiplier: `2`

These controls change indexing or retrieval internals only. They do not alter the `QueryRequest` surface or default query semantics unless you opt in through the builder.

Spring Boot properties mirror the same knobs:

```yaml
lightrag:
  indexing:
    chunking:
      window-size: 600
      overlap: 80
    embedding-batch-size: 32
    max-parallel-insert: 4
    entity-extract-max-gleaning: 1
    max-extract-input-tokens: 20480
    language: Chinese
    entity-types: Person,Organization
  query:
    automatic-keyword-extraction: false
    rerank-candidate-multiplier: 4
```

Compatibility note:

- these controls stay on `LightRag.builder()` and starter/runtime wiring; the public `LightRagConfig(...)` constructor remains backward compatible in this phase
- the new pipeline controls are builder-level and starter-level options, not new `QueryRequest` fields
- external integrations should prefer `LightRag.builder()` for new pipeline tuning

## Evaluation CLI

The evaluation tasks now emit structured JSON envelopes so different runs can be compared more reliably.

Single-query evaluation:

```bash
./gradlew :lightrag-core:runRagasQuery --args="\
  --documents-dir /tmp/docs \
  --question 'Who works with Bob?' \
  --mode NAIVE \
  --top-k 5 \
  --chunk-top-k 7"
```

Output shape:

```json
{
  "request": {
    "documentsDir": "/tmp/docs",
    "question": "Who works with Bob?",
    "mode": "NAIVE",
    "topK": 5,
    "chunkTopK": 7
  },
  "result": {
    "answer": "Alice works with Bob.",
    "contexts": [
      {
        "sourceId": "chunk-1",
        "referenceId": "",
        "source": "",
        "text": "Alice works with Bob"
      }
    ]
  }
}
```

Batch evaluation:

```bash
./gradlew :lightrag-core:runRagasBatchEval --args="\
  --documents-dir /tmp/docs \
  --dataset /tmp/dataset.json \
  --mode MIX \
  --top-k 10 \
  --chunk-top-k 10 \
  --storage-profile in-memory \
  --run-label baseline"
```

For a fixed local baseline, the batch CLI now defaults to the bundled sample corpus and sample dataset:

```bash
./gradlew :lightrag-core:runRagasBatchEval --args="--run-label baseline"
```

You can compare a candidate configuration by re-running the same dataset with a different label and retrieval knobs:

```bash
./gradlew :lightrag-core:runRagasBatchEval --args="\
  --run-label candidate-rerank-4 \
  --top-k 10 \
  --chunk-top-k 20 \
  --mode MIX \
  --storage-profile in-memory"
```

Batch output now includes:

- `request`: run-level parameters such as `mode`, `topK`, `chunkTopK`, `storageProfile`, and `runLabel`
- `summary.totalCases`: quick run-size check
- `results[*]`: `caseIndex`, `question`, `groundTruth`, `caseMetadata`, `answer`, structured `contexts`, and structured `references`

Compatibility note:

- `RagasEvaluationService.EvaluationResult` and `RagasBatchEvaluationService.Result` now expose structured `QueryResult.Context` entries instead of plain strings
- if your integration previously consumed `List<String>` contexts, read `context.text()` from each returned context object
- `evaluation/ragas/eval_rag_quality_java.py` remains compatible with both the legacy list payload and the new batch envelope

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
