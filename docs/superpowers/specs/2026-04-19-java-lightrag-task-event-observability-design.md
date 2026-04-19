# Java LightRAG Task Event Observability Design

## Overview

This document defines a unified task-observability design for Java LightRAG ingestion and rebuild workflows.

The current repository already contains two overlapping async/task models:

- core-level task execution through `TaskExecutionService`
- demo-level ingestion job management through `IngestJobService`

This split causes three user-visible problems:

- graph construction is difficult to diagnose when it is slow because model latency, vector latency, graph persistence latency, queue wait time, and commit-lock wait time are not reported through one model
- async task APIs only expose coarse job status and do not provide document-level or chunk-level graph/vector visibility
- callers cannot register typed Java callbacks to synchronize side effects with ingestion progress

The target direction is:

- make the core task model the single async abstraction
- publish fine-grained ingestion events from the indexing pipeline
- project event summaries into queryable task/document/chunk progress views
- expose Java listener registration for real-time event consumption
- log detailed performance timing while only persisting task and document timing summaries

## Goals

- Use `TaskExecutionService` as the single async execution model for ingest and rebuild operations.
- Replace demo-local ingestion job tracking with core task submission and task queries.
- Add a unified task event model that covers:
  - task lifecycle
  - stage lifecycle
  - document progress
  - chunk graph progress
  - chunk vector progress
  - commit and timing diagnostics
- Expose Java event listeners at:
  - global `LightRagBuilder` scope
  - per-task submission scope
- Keep listener execution weakly synchronous:
  - listeners run inline with task execution
  - listener failures are recorded but must not fail the main task
- Add task and document summary APIs that expose:
  - overall task progress
  - document graph progress
  - document vector progress
  - aggregated timing summaries
- Add chunk-level drill-down queries for graph and vector troubleshooting.
- Emit structured logs for detailed slow-path diagnosis.

## Non-Goals

- Do not add HTTP webhook callbacks in this phase.
- Do not add a full event-sourcing or replay system.
- Do not persist every performance sample or every chunk event indefinitely.
- Do not redesign extraction semantics to batch multiple chunks into one LLM extraction request.
- Do not change current fail-fast task semantics into partial-success task completion in this phase.
- Do not preserve or extend the existing demo-specific `IngestJobService` model.

## Current Problems

### 1. Async execution is split

`LightRag` already has core task APIs:

- `submitIngest(...)`
- `submitIngestSources(...)`
- `submitRebuild(...)`
- `getTask(...)`
- `listTasks(...)`
- `cancelTask(...)`

The Spring demo still routes document ingestion through `IngestJobService`, which creates a second task model with:

- a different identifier type
- a different status model
- separate retry and cancel behavior
- no stage-level or document-level progress

This prevents one consistent API surface for ingestion, rebuild, repair, and future task types.

### 2. Slow-path diagnosis is incomplete

`IndexingPipeline` already has logical stage boundaries, but current progress reporting is too coarse:

- `IndexingProgressListener` only reports stage started / succeeded / skipped
- there is no structured document event or chunk event model
- timing is not aggregated into task summaries
- detailed timing is not logged in one consistent format

As a result, users cannot answer:

- is the chat model extraction slow
- is embedding slow
- is graph merge or graph persistence slow
- is vector persistence slow
- is queueing or commit serialization the real bottleneck

### 3. Fine-grained graph/vector progress is not queryable

Current task snapshots expose only task-level stage state.

Users also need:

- overall task progress
- document-level graph progress
- document-level vector progress
- chunk-level graph and vector drill-down

### 4. No Java callback contract exists for task progress

Core code has an internal progress listener, but there is no public typed callback contract for callers to hook into task progress and run synchronous side effects.

## Design Summary

The design introduces a unified task event bus around core task execution:

```text
LightRag
  -> TaskExecutionService
      -> TaskEventPublisher
          -> ListenerDispatcher
          -> ProgressProjector
      -> IndexingPipeline / DeletionPipeline / Graph repair pipelines
```

The responsibilities are:

- `TaskExecutionService`
  - remains the single async execution service
  - owns task lifecycle state
- `TaskEventPublisher`
  - accepts normalized task events
  - dispatches them to Java listeners
  - forwards them to progress projectors
- `ProgressProjector`
  - stores query-oriented summaries for tasks, documents, and selected chunk details
- pipeline code
  - emits business events and timing measurements
  - does not own external callback contracts

The design keeps two complementary observability channels:

- real-time event listeners for callers
- query APIs for dashboards and troubleshooting

## Architecture Changes

### 1. Core task model becomes the only async model

`LightRag` remains the public entry point for async work.

The demo layer must stop storing ingestion jobs in `IngestJobService` and instead delegate to:

- `LightRag.submitIngest(...)`
- `LightRag.submitIngestSources(...)`
- `LightRag.getTask(...)`
- `LightRag.listTasks(...)`
- `LightRag.cancelTask(...)`

The demo controllers become thin adapters on top of core tasks.

### 2. Add task event publishing

Introduce a new public event model and an internal publisher abstraction:

- `TaskEvent`
- `TaskEventType`
- `TaskEventScope`
- `TaskEventPublisher`
- `TaskEventListener`

The publisher fans events out to:

- registered listeners
- summary projectors
- structured performance logging

### 3. Add summary storage for queries

Current `TaskStore` and `TaskStageStore` are not enough for the required document/chunk observability.

Add new summary storage abstractions:

- `TaskDocumentStore`
- `TaskChunkStore`

Responsibilities:

- `TaskDocumentStore`
  - persist per-task per-document summaries
  - support list-by-task and load-by-task-document queries
- `TaskChunkStore`
  - persist chunk drill-down summaries
  - support list-by-task-document and load-by-task-document-chunk queries

The task summary in `TaskStore` remains the authoritative task lifecycle record.

### 4. Keep chunk persistence selective

Chunk observability is required, but default task queries must stay light.

The design therefore uses:

- task summaries for task overviews
- document summaries for document-level progress
- chunk summaries only for drill-down queries and failure analysis

The default task overview must not inline all chunk details.

## Event Model

### Common Event Shape

All published events share one stable envelope:

- `eventId`
- `eventType`
- `scope`
- `occurredAt`
- `workspaceId`
- `taskId`
- `taskType`
- `documentId` nullable
- `chunkId` nullable
- `stage` nullable
- `status`
- `message`
- `attributes`

`attributes` carries extensible structured fields such as:

- `chunkCount`
- `entityCount`
- `relationCount`
- `vectorCount`
- `durationMs`
- `queueWaitMs`
- `lockWaitMs`
- `batchSize`
- `modelName`
- `namespace`

This keeps the top-level model stable while allowing additional counters to be added without changing the event envelope.

### Event Scopes

Supported event scopes are:

- `TASK`
- `STAGE`
- `DOCUMENT`
- `CHUNK`
- `GRAPH`
- `VECTOR`
- `DIAGNOSTIC`
- `LISTENER`

### Event Types

Event types should stay normalized rather than exploding into many unrelated classes.

The initial event types are:

- `TASK.SUBMITTED`
- `TASK.RUNNING`
- `TASK.SUCCEEDED`
- `TASK.FAILED`
- `TASK.CANCELLED`
- `TASK.METADATA_UPDATED`
- `STAGE.STARTED`
- `STAGE.SUCCEEDED`
- `STAGE.SKIPPED`
- `STAGE.FAILED`
- `DOCUMENT.STARTED`
- `DOCUMENT.CHUNKED`
- `DOCUMENT.GRAPH_READY`
- `DOCUMENT.VECTORS_READY`
- `DOCUMENT.COMMITTED`
- `DOCUMENT.FAILED`
- `CHUNK.CREATED`
- `CHUNK.PRIMARY_EXTRACTED`
- `CHUNK.REFINED`
- `CHUNK.GRAPH_ASSEMBLED`
- `CHUNK.VECTOR_READY`
- `CHUNK.COMMITTED`
- `CHUNK.FAILED`
- `GRAPH.COMMITTED`
- `VECTOR.COMMITTED`
- `DIAGNOSTIC.TIMING`
- `LISTENER.FAILED`

The exact type list can grow, but the scope-based model is fixed.

## Listener Design

### Public API

Expose one public listener interface:

```java
public interface TaskEventListener {
    void onEvent(TaskEvent event);
}
```

Registration points:

- global registration on `LightRagBuilder`
- per-task registration through task submit options

Public API shape:

```java
LightRag rag = LightRag.builder()
    .taskEventListener(globalListener)
    .build();
```

```java
String taskId = rag.submitIngest(
    workspaceId,
    documents,
    TaskSubmitOptions.builder()
        .listener(taskScopedListener)
        .build()
);
```

### Listener Semantics

Listener dispatch rules are:

- listeners run inline on the main task thread
- listeners are weakly synchronous
- listener failures must not fail the main task
- events for the same task are dispatched in emission order
- global ordering across unrelated tasks is not guaranteed

### Listener Failure Handling

When a listener throws:

- catch the failure
- log a structured listener failure message
- publish a `LISTENER.FAILED` diagnostic event when possible
- increment task metadata counters such as:
  - `listenerFailureCount`
  - `lastListenerError`

Main task execution must continue.

## Query API Design

### Task Overview Queries

Keep and extend:

- `TaskSnapshot getTask(String workspaceId, String taskId)`
- `List<TaskSnapshot> listTasks(String workspaceId)`

Extend the task snapshot payload to include summary progress and timing fields:

- `documentTotal`
- `documentSucceeded`
- `documentFailed`
- `documentRunning`
- `chunkTotal`
- `chunkSucceeded`
- `chunkFailed`
- `entityTotal`
- `relationTotal`
- `vectorTotal`
- `queueWaitMs`
- `totalDurationMs`
- `modelDurationMs`
- `embeddingDurationMs`
- `storageDurationMs`
- `commitDurationMs`
- `lockWaitMs`

These values are aggregate summaries derived from task events.

### Document Queries

Add typed API methods:

- `List<TaskDocumentSnapshot> listTaskDocuments(String workspaceId, String taskId, int page, int size)`
- `TaskDocumentSnapshot getTaskDocument(String workspaceId, String taskId, String documentId)`

`TaskDocumentSnapshot` should include:

- `documentId`
- `status`
- `currentStage`
- `chunkTotal`
- `chunkCompleted`
- `chunkFailed`
- `entityCount`
- `relationCount`
- `graphStatus`
- `chunkVectorStatus`
- `entityVectorStatus`
- `relationVectorStatus`
- `vectorCount`
- `parsingDurationMs`
- `chunkingDurationMs`
- `extractionDurationMs`
- `embeddingDurationMs`
- `commitDurationMs`
- `errorMessage`

### Chunk Drill-Down Queries

Add typed API methods:

- `List<TaskChunkSnapshot> listTaskDocumentChunks(String workspaceId, String taskId, String documentId, int page, int size)`
- `TaskChunkSnapshot getTaskDocumentChunk(String workspaceId, String taskId, String documentId, String chunkId)`

`TaskChunkSnapshot` should include:

- `chunkId`
- `order`
- `textPreview`
- `primaryExtractionStatus`
- `refinementStatus`
- `graphAssemblyStatus`
- `chunkVectorStatus`
- `entityVectorStatus`
- `relationVectorStatus`
- `commitStatus`
- `entityCount`
- `relationCount`
- `modelDurationMs`
- `embeddingDurationMs`
- `commitDurationMs`
- `errorMessage`

Chunk drill-down is intended for troubleshooting only and must not be embedded by default into task overview queries.

## Spring Demo API Changes

The demo HTTP layer should align with the core task APIs and stop exposing a separate ingest-job concept.

Demo endpoints:

- `POST /documents/ingest`
  - submit ingest
  - return `taskId`
- `GET /tasks`
  - list task summaries
- `GET /tasks/{taskId}`
  - return task summary
- `GET /tasks/{taskId}/documents`
  - return document summaries
- `GET /tasks/{taskId}/documents/{documentId}`
  - return one document summary
- `GET /tasks/{taskId}/documents/{documentId}/chunks`
  - return chunk drill-down summaries
- `GET /tasks/{taskId}/documents/{documentId}/chunks/{chunkId}`
  - return one chunk summary
- `POST /tasks/{taskId}/cancel`
  - cancel a task

This gives one consistent model for ingest, rebuild, and future task types.

## Performance Diagnosis Design

Detailed slow-path diagnosis should rely on structured logs, while APIs only expose aggregated timing summaries.

### Detailed Timing Logs

Emit structured logs for:

- task queue wait
- task total duration
- document parsing duration
- document chunking duration
- document extraction duration
- document embedding duration
- document commit duration
- chunk primary extraction duration
- chunk refinement duration
- chunk embedding duration
- entity embedding duration
- relation embedding duration
- graph save duration
- vector save duration
- commit-lock wait duration

Representative log format:

```text
task_event=PERF taskId=... workspaceId=... scope=document documentId=doc-1 phase=embedding durationMs=842 chunkCount=16 vectorCount=16
task_event=PERF taskId=... workspaceId=... scope=commit documentId=doc-1 phase=graph_save durationMs=193 entityCount=24 relationCount=31
task_event=PERF taskId=... workspaceId=... scope=task phase=queue_wait durationMs=1276
```

### Persisted Timing Summaries

Persist only task-level and document-level timing aggregates:

- task
  - `queueWaitMs`
  - `totalDurationMs`
  - `modelDurationMs`
  - `embeddingDurationMs`
  - `storageDurationMs`
  - `commitDurationMs`
  - `lockWaitMs`
- document
  - `parsingDurationMs`
  - `chunkingDurationMs`
  - `extractionDurationMs`
  - `embeddingDurationMs`
  - `commitDurationMs`

Per-call timing samples should remain in logs only.

## Batch Processing Strategy

This phase should not aggressively redesign all indexing work into large batch units.

### Batch-friendly operations

Prioritize batching where the code and storage model already support it:

- chunk embedding
- entity embedding
- relation embedding
- vector `saveAll(...)`
- graph `saveEntities(...)`
- graph `saveRelations(...)`

### Operations that should remain document/chunk scoped

Do not batch these into multi-chunk LLM requests in this phase:

- primary extraction
- refinement extraction

Reasons:

- attribution becomes harder
- error isolation gets worse
- extraction quality risks increase
- the required user-visible gain is still unknown without timing data

### Expected initial bottlenecks

Based on current code paths, the likely first-order bottlenecks are:

1. demo-level single-threaded async execution
2. commit-stage serialization through `storageMutationMonitor`
3. graph load-and-merge persistence cost
4. extraction model latency
5. embedding batch-size tuning

The first implementation step should measure and expose these costs before changing ingest semantics further.

## Failure And Consistency Design

### Failure categories

Three failure classes must be treated separately:

1. main processing failures
   - parsing
   - extraction
   - embedding
   - graph persistence
   - vector persistence
2. listener failures
   - callback exceptions
3. projection failures
   - summary store update failures

### Propagation rules

Main processing failures:

- mark the current document failed
- mark the current task failed under current fail-fast semantics
- preserve clear stage and error diagnostics

Listener failures:

- record and continue
- never fail the task

Projection failures:

- log them explicitly
- fail the task if the summary state cannot remain trustworthy

The design intentionally keeps current fail-fast behavior for multi-document tasks.

### Consistency boundaries

Strong consistency is required for:

- task lifecycle state
- stage lifecycle state
- document summary state

Chunk summary state is intended for troubleshooting and can remain a derived drill-down view, but failed chunk diagnostics must be queryable.

## Testing Strategy

Required coverage includes four layers.

### 1. Unit tests

- task event construction and validation
- listener registration and dispatch ordering
- listener failure isolation
- task/document/chunk summary projection
- timing aggregation logic
- DTO mapping and controller response mapping

### 2. Pipeline integration tests

- successful async ingest produces task, document, and chunk summaries
- failing ingest records correct task, document, and stage failures
- graph/vector chunk events advance summaries correctly
- timing summaries are aggregated into task and document views

### 3. Listener tests

- global listeners receive events
- task-scoped listeners only receive their own task events
- events are ordered within one task
- listener exceptions do not fail the main task
- listener failures are logged and surfaced in task metadata

### 4. Demo and API regression tests

- `POST /documents/ingest` returns a core `taskId`
- task list and detail endpoints return expected summaries
- document and chunk drill-down endpoints return expected summaries
- cancel behavior remains correct
- existing document-status and graph-inspection flows continue to work

Verification should include targeted module tests and at least:

- `./gradlew :lightrag-core:test`
- `./gradlew :lightrag-spring-boot-demo:test`

## Migration Plan

Implementation should proceed in these phases:

1. add public event model and listener registration APIs
2. add task document and chunk summary stores plus DTOs
3. emit ingestion pipeline events and timing measurements
4. project events into task/document/chunk summaries
5. migrate demo controllers from `IngestJobService` to core task APIs
6. add task/document/chunk query endpoints
7. add structured performance logs and summary timing fields

This preserves a clear upgrade path while keeping the core task model as the final source of truth.
