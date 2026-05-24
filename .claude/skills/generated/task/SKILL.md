---
name: task
description: "Skill for the Task area of lightrag-java. 85 symbols across 12 files."
---

# Task

85 symbols | 12 files | Cohesion: 73%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how TaskReporter, cancelTask, onEvent work
- Modifying task-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java` | cancel, runTask, recoverInterruptedTasks, copyTask, sequence (+36) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | ingestSources, computeDocument, computeDocument, computeDocument, publishPendingChunkTasks (+10) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingProgressListener.java` | onStageStarted, onStageSucceeded, onStageSkipped, onDocumentStarted, onDocumentChunked (+6) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | inspect, materialize, materializeChunk, resolveMode, persistSnapshotIfConfigured |
| `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | cancelTask, getTask, listTasks |
| `lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java` | awaitBranch, unwrapCompletionException |
| `lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java` | awaitBranch, unwrapCompletionException |
| `lightrag-core/src/main/java/io/github/lightrag/task/TaskMetadataReporter.java` | updateMetadata, TaskMetadataReporter |
| `lightrag-core/src/main/java/io/github/lightrag/api/TaskEventListener.java` | onEvent |
| `lightrag-core/src/main/java/io/github/lightrag/api/TaskStatus.java` | isTerminal |

## Entry Points

Start here when exploring this area:

- **`TaskReporter`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java:399`
- **`cancelTask`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java:234`
- **`onEvent`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/TaskEventListener.java:4`
- **`isTerminal`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/TaskStatus.java:9`
- **`inspect`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java:128`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `TaskReporter` | Class | `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java` | 399 |
| `IndexingProgressListener` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingProgressListener.java` | 5 |
| `TaskMetadataReporter` | Interface | `lightrag-core/src/main/java/io/github/lightrag/task/TaskMetadataReporter.java` | 5 |
| `cancelTask` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 234 |
| `onEvent` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/TaskEventListener.java` | 4 |
| `isTerminal` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/TaskStatus.java` | 9 |
| `inspect` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 128 |
| `materialize` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 138 |
| `materializeChunk` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 202 |
| `resolveMode` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 533 |
| `persistSnapshotIfConfigured` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 694 |
| `ingestSources` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 411 |
| `computeDocument` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 636 |
| `computeDocument` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 681 |
| `computeDocument` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 729 |
| `publishPendingChunkTasks` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 791 |
| `refineExtractions` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 808 |
| `refineExtractions` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 812 |
| `saveFailureStatus` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 1001 |
| `toDocument` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 1186 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `ComputeDocument → Get` | cross_community | 6 |
| `ComputeDocument → Equals` | cross_community | 5 |
| `ComputeDocument → WithReadLock` | cross_community | 5 |
| `ComputeDocument → Key` | cross_community | 5 |
| `ComputeDocument → Metadata` | cross_community | 5 |
| `IngestSources → List` | cross_community | 5 |
| `IngestSources → List` | cross_community | 5 |
| `IngestSources → DocumentGraphSnapshotStore` | cross_community | 5 |
| `IngestSources → DocumentGraphJournalStore` | cross_community | 5 |
| `IngestSources → DocumentStore` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Indexing | 31 calls |
| Storage | 12 calls |
| Postgres | 6 calls |
| Api | 5 calls |
| Refinement | 1 calls |
| Query | 1 calls |
| Boot | 1 calls |

## How to Explore

1. `gitnexus_context({name: "TaskReporter"})` — see callers and callees
2. `gitnexus_query({query: "task"})` — find related execution flows
3. Read key files listed above for implementation details
