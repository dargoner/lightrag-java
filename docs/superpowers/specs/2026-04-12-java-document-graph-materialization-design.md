# Java Document Graph Materialization Design

## Summary

This design turns per-document graph construction from a one-shot ingest side effect into a durable materialization phase that is inspectable, resumable, repairable, and rebuildable.

The first version is intentionally scoped to a single `documentId` inside one workspace:

- document-level `inspect` and `materialize`
- chunk-level status queries plus targeted `resume` and `repair`
- persistent status data and task metadata
- task-query compatibility through the existing SDK task API

The design explicitly does not include chunk-level rebuild, batch recovery orchestration, or global cross-document consistency repair.

## Goals

- Persist enough state to inspect graph materialization truth after partial failure or process restart.
- Support document-level `AUTO`, `RESUME`, `REPAIR`, and `REBUILD`.
- Support chunk-level status queries plus targeted `resume` and `repair`.
- Keep recovery logic separate from the normal ingest path.
- Integrate with the existing SDK task model so callers can query recovery progress through `getTask(...)` and `listTasks(...)`.

## Non-Goals

- Batch-level recovery orchestration across many documents.
- Chunk-level rebuild semantics.
- Strong transactional rollback across graph store and vector store writes.
- Demo controller and UI work in the first implementation.
- Workspace-global graph health repair.

## Current Problems

Today the Java SDK persists document/chunk/graph/vector data through the normal ingest path, but it does not persist graph-materialization execution truth at document or chunk granularity.

That leaves several gaps:

- partial graph and vector writes can happen without a durable "what succeeded" record
- `DocumentStatusStore` only describes coarse document state, not graph-materialization state
- callers cannot inspect per-chunk graph progress
- callers cannot safely resume or repair a single document or a single chunk
- task snapshots show pipeline stage progress, but not graph-recovery-specific status or counts

## Design Principles

- Persist state truth: recovery must be based on durable snapshot and journal data, not inference from logs.
- Keep recovery isolated: normal ingest remains in `IndexingPipeline`; recovery lives in a dedicated pipeline.
- Make chunks first-class: every chunk must be inspectable and selectively recoverable.
- Keep document status lightweight: `DocumentStatusStore` stays a summary layer, not the full recovery truth.
- Allow partial materialization: partial state is acceptable if `inspect`, `resume`, and `repair` can converge it to correctness.

## High-Level Architecture

```text
LightRag
  |- IndexingPipeline
  |    |- writes extraction snapshot
  |    |- initializes graph journal
  |    `- performs first graph materialization
  |
  `- GraphMaterializationPipeline
       |- inspect
       |- resume
       |- repair
       `- rebuild
            |- DocumentGraphSnapshotStore
            |- DocumentGraphJournalStore
            |- DocumentStatusStore
            |- GraphStore
            `- VectorStore
```

Normal ingest remains responsible for building the first durable graph snapshot and first materialization attempt.

Recovery operations do not reuse full ingest end-to-end. They reuse snapshot data and targeted graph/vector write helpers through a new `GraphMaterializationPipeline`.

## Persistent Model

### DocumentGraphSnapshotStore

Purpose:

- persist extraction output per document and per chunk
- provide the primary recovery input for `resume` and `repair`
- support degraded snapshot recovery from existing durable state when possible

Document-level record:

```java
record DocumentGraphSnapshot(
    String documentId,
    int version,
    SnapshotStatus status,
    SnapshotSource source,
    int chunkCount,
    Instant createdAt,
    Instant updatedAt,
    String errorMessage
) {}
```

`SnapshotStatus`:

- `BUILDING`
- `READY`
- `PARTIAL`
- `FAILED`

`SnapshotSource`:

- `PRIMARY_EXTRACTION`
- `RECOVERED_FROM_STORAGE`

Chunk-level record:

```java
record ChunkGraphSnapshot(
    String documentId,
    String chunkId,
    int chunkOrder,
    String contentHash,
    ChunkExtractStatus extractStatus,
    List<ExtractedEntityRecord> entities,
    List<ExtractedRelationRecord> relations,
    Instant updatedAt,
    String errorMessage
) {}
```

`ChunkGraphSnapshot` persists the finalized extraction result for the chunk. When contextual extraction refinement is enabled through `ExtractionRefinementOptions`, the snapshot must store the post-refinement entities and relations rather than only the primary extraction output.

`ChunkExtractStatus`:

- `NOT_STARTED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`

### DocumentGraphJournalStore

Purpose:

- record graph materialization expectations and progress
- provide durable execution truth for inspect and recovery
- support chunk-level targeted operations

Document-level record:

```java
record DocumentGraphJournal(
    String documentId,
    int snapshotVersion,
    GraphMaterializationStatus status,
    GraphMaterializationMode lastMode,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    FailureStage lastFailureStage,
    Instant createdAt,
    Instant updatedAt,
    String errorMessage
) {}
```

Chunk-level record:

```java
record ChunkGraphJournal(
    String documentId,
    String chunkId,
    int snapshotVersion,
    ChunkMergeStatus mergeStatus,
    ChunkGraphStatus graphStatus,
    List<String> expectedEntityKeys,
    List<String> expectedRelationKeys,
    List<String> materializedEntityKeys,
    List<String> materializedRelationKeys,
    FailureStage lastFailureStage,
    Instant updatedAt,
    String errorMessage
) {}
```

`GraphMaterializationStatus`:

- `NOT_STARTED`
- `MERGING`
- `PARTIAL`
- `MERGED`
- `FAILED`
- `STALE`
- `MISSING`

`ChunkMergeStatus`:

- `NOT_STARTED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`

`ChunkGraphStatus`:

- `NOT_MATERIALIZED`
- `MATERIALIZED`
- `PARTIAL`
- `FAILED`
- `STALE`
- `MISSING`

`FailureStage`:

- `SNAPSHOT_LOADING`
- `SNAPSHOT_RECOVERY`
- `GRAPH_INSPECTION`
- `ENTITY_MATERIALIZATION`
- `RELATION_MATERIALIZATION`
- `VECTOR_REPAIR`
- `FINALIZING`

### DocumentStatusStore Extensions

`DocumentStatusStore` remains the summary layer. It should expose or derive:

- `graphStatus`
- `snapshotStatus`
- `recommendedMode`

It must not become the primary recovery truth.

## State Machines

### Document-Level Materialization

```text
NOT_STARTED -> MERGING
MERGING -> MERGED
MERGING -> PARTIAL
MERGING -> FAILED
PARTIAL -> MERGING
PARTIAL -> MERGED
PARTIAL -> FAILED
FAILED -> MERGING
MERGED -> STALE
MERGED -> MISSING
STALE -> MERGING
MISSING -> MERGING
```

### Chunk-Level Materialization

```text
extractStatus:
NOT_STARTED -> RUNNING -> SUCCEEDED | FAILED

mergeStatus:
NOT_STARTED -> RUNNING -> SUCCEEDED | FAILED

graphStatus:
NOT_MATERIALIZED -> MATERIALIZED | PARTIAL | FAILED | MISSING | STALE
PARTIAL -> MATERIALIZED | FAILED
FAILED -> PARTIAL | MATERIALIZED
MATERIALIZED -> STALE | MISSING
```

Document graph status is a reduction over all chunk graph states with the following precedence:

- all chunks `NOT_MATERIALIZED` and no active merge => document `NOT_STARTED`
- any chunk `MISSING` => document `MISSING`
- any chunk `STALE` and no chunk `MISSING` => document `STALE`
- all chunks `MATERIALIZED` => document `MERGED`
- any chunk `PARTIAL` => document `PARTIAL`
- any chunk `FAILED` and no active merge => document `FAILED`
- otherwise, if a merge is active => document `MERGING`

## SDK API

### Document-Level Sync API

```java
DocumentGraphInspection inspectDocumentGraph(String workspaceId, String documentId);

DocumentGraphMaterializationResult materializeDocumentGraph(
    String workspaceId,
    String documentId,
    GraphMaterializationMode mode
);
```

`GraphMaterializationMode`:

- `AUTO`
- `RESUME`
- `REPAIR`
- `REBUILD`

Suggested result types:

```java
record DocumentGraphInspection(
    String documentId,
    DocumentStatus documentStatus,
    GraphMaterializationStatus graphStatus,
    SnapshotStatus snapshotStatus,
    int snapshotVersion,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    List<String> missingEntityKeys,
    List<String> missingRelationKeys,
    List<String> orphanEntityKeys,
    List<String> orphanRelationKeys,
    GraphMaterializationMode recommendedMode,
    boolean repairable,
    String summary
) {}

record DocumentGraphMaterializationResult(
    String documentId,
    GraphMaterializationMode requestedMode,
    GraphMaterializationMode executedMode,
    GraphMaterializationStatus finalStatus,
    int snapshotVersion,
    int entitiesExpected,
    int relationsExpected,
    int entitiesMaterialized,
    int relationsMaterialized,
    boolean snapshotReused,
    boolean snapshotRecoveredFromStorage,
    String summary,
    String errorMessage
) {}
```

### Chunk-Level Sync API

```java
DocumentChunkGraphStatus getDocumentChunkGraphStatus(
    String workspaceId,
    String documentId,
    String chunkId
);

List<DocumentChunkGraphStatus> listDocumentChunkGraphStatuses(
    String workspaceId,
    String documentId
);

ChunkGraphMaterializationResult resumeChunkGraph(
    String workspaceId,
    String documentId,
    String chunkId
);

ChunkGraphMaterializationResult repairChunkGraph(
    String workspaceId,
    String documentId,
    String chunkId
);
```

`GraphChunkAction`:

- `NONE`
- `RESUME`
- `REPAIR`

Suggested result types:

```java
record DocumentChunkGraphStatus(
    String documentId,
    String chunkId,
    int chunkOrder,
    ChunkExtractStatus extractStatus,
    ChunkMergeStatus mergeStatus,
    ChunkGraphStatus graphStatus,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    List<String> missingEntityKeys,
    List<String> missingRelationKeys,
    boolean repairable,
    GraphChunkAction recommendedAction,
    String errorMessage
) {}

record ChunkGraphMaterializationResult(
    String documentId,
    String chunkId,
    GraphChunkAction executedAction,
    ChunkGraphStatus finalStatus,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    String summary,
    String errorMessage
) {}
```

Chunk-level rebuild is intentionally excluded from the first version.

### Async Submission API

The first version should also support task submission:

```java
String submitDocumentGraphMaterialization(
    String workspaceId,
    String documentId,
    GraphMaterializationMode mode
);

String submitChunkGraphMaterialization(
    String workspaceId,
    String documentId,
    String chunkId,
    GraphChunkAction action
);
```

Task querying should reuse existing APIs:

- `getTask(...)`
- `listTasks(...)`

## Mode Selection Rules

### Document-Level AUTO

```text
if snapshot is not READY:
    if snapshot can be recovered from durable state:
        recover snapshot
    else:
        execute REBUILD

if graphStatus == PARTIAL:
    execute RESUME

if graphStatus in {MISSING, STALE, FAILED}:
    execute REPAIR

if graphStatus == NOT_STARTED:
    execute RESUME

if graphStatus == MERGED:
    no-op
```

### Chunk-Level

```text
if extractStatus != SUCCEEDED:
    reject chunk resume/repair

if graphStatus == PARTIAL:
    RESUME

if graphStatus in {NOT_MATERIALIZED, MISSING, STALE, FAILED}:
    REPAIR

if graphStatus == MATERIALIZED:
    no-op
```

## Integration Points in Current Java Code

### LightRag

Add the new sync and async graph-materialization methods to `LightRag`.

### LightRagBuilder

Validate that the configured storage provider exposes:

- `DocumentGraphSnapshotStore`
- `DocumentGraphJournalStore`

### StorageProvider and AtomicStorageProvider

Add:

```java
DocumentGraphSnapshotStore documentGraphSnapshotStore();
DocumentGraphJournalStore documentGraphJournalStore();
```

### StorageCoordinator and Snapshot Support

Snapshot capture and restore must include the new stores so in-memory and PostgreSQL recovery stay aligned with existing snapshot behavior.

### IndexingPipeline

The normal ingest path remains the first writer of snapshot and journal truth:

1. after primary extraction and optional refinement, write chunk extraction snapshot
   - when `ExtractionRefinementOptions.enabled()` is true, persist the refined extraction result as the snapshot truth
2. before graph/vector materialization, initialize document and chunk journals
3. as entity and relation writes succeed, advance journal progress
4. only after successful finalization update the document summary state to healthy completion

`IndexingPipeline` should not absorb recovery semantics beyond first-attempt snapshot/journal production.

### GraphMaterializationPipeline

Introduce a dedicated pipeline that:

- loads snapshot and journal state
- inspects graph/vector consistency
- resumes missing entity or relation writes
- repairs missing or stale writes
- performs document-level rebuild when explicitly requested or required by `AUTO`

This pipeline should reuse existing graph/vector write helpers where possible, but must not invoke full normal ingest end-to-end.

## Task Design

### Task Types

Add task types:

- `MATERIALIZE_DOCUMENT_GRAPH`
- `MATERIALIZE_CHUNK_GRAPH`

### Task Stages

Add materialization-specific stages:

- `SNAPSHOT_LOADING`
- `SNAPSHOT_RECOVERY`
- `GRAPH_INSPECTION`
- `ENTITY_MATERIALIZATION`
- `RELATION_MATERIALIZATION`
- `VECTOR_REPAIR`
- `FINALIZING`

### Task Metadata

Task metadata should be stored in the existing `TaskSnapshot.metadata` string map.

Document task metadata should include:

- `documentId`
- `requestedMode`
- `executedMode`
- `graphStatus`
- `snapshotVersion`
- `chunkCount`
- `expectedEntityCount`
- `expectedRelationCount`
- `materializedEntityCount`
- `materializedRelationCount`
- `repairable`

Chunk task metadata should include:

- `documentId`
- `chunkId`
- `requestedAction`
- `executedAction`
- `graphStatus`
- `snapshotVersion`
- `expectedEntityCount`
- `expectedRelationCount`
- `materializedEntityCount`
- `materializedRelationCount`

## Error Handling and Consistency

### Durable Truth

Recovery truth is:

1. `DocumentGraphSnapshotStore`
2. `DocumentGraphJournalStore`

`DocumentStatusStore` is only a summary projection.

### Partial Success

The first version accepts partial graph/vector success. It does not require cross-store rollback of all successful writes.

Instead:

- snapshot must be durable
- journal must be durable
- partial graph/vector writes are allowed
- `inspect`, `resume`, and `repair` must converge partial state to correctness

### Journal Failure Rule

If graph/vector writes succeed but journal update fails:

- the operation is treated as failed
- the resulting state must be visible as `PARTIAL` or `FAILED`
- document summary state must not claim healthy completion

## Testing Strategy

### Store Unit Tests

- in-memory snapshot store behavior
- in-memory journal store behavior
- PostgreSQL store persistence and workspace isolation
- snapshot capture and restore coverage

### Pipeline Unit Tests

- `inspectDocumentGraph(...)`
- document `AUTO`, `RESUME`, `REPAIR`, `REBUILD`
- chunk `resume`
- chunk `repair`
- mode selection correctness

### Integration Tests

Key scenarios:

- entity materialization fails after some successful entity writes -> `PARTIAL`
- relation materialization fails after some successful relation writes -> `PARTIAL`
- `RESUME` converges partial state to `MERGED`
- graph/vector drift is detected and fixed by `REPAIR`
- snapshot missing but recoverable from durable state -> `AUTO` recovers then repairs
- chunk-specific repair only affects the targeted chunk's missing materialization
- task metadata and task stages reflect real progress

## Implementation Order

### Phase 1

- add snapshot store SPI and implementations
- add journal store SPI and implementations
- include stores in snapshot capture/restore
- write snapshot and journal during normal ingest
- add inspection and status-query APIs

### Phase 2

- add `GraphMaterializationPipeline`
- add document `AUTO|RESUME|REPAIR|REBUILD`
- add chunk `resume|repair`
- add async task submission and task metadata

### Phase 3

- PostgreSQL optimization and migration hardening
- additional inspection performance improvements
- optional controller/demo integration

## Risks and Trade-Offs

- Partial writes remain possible in the first version by design.
- Chunk-level rebuild is intentionally excluded because entity and relation semantics are cross-chunk aggregated.
- Recovery complexity increases storage surface area and test surface area.
- The design favors durable recovery truth over minimal schema changes.

## Recommendation

Implement the first version with explicit `snapshot + journal + task metadata + status query` support.

This is the smallest design that makes graph materialization:

- inspectable
- resumable
- repairable
- rebuildable

without forcing a full rewrite of the current ingest architecture.
