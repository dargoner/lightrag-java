# Java LightRAG Resumable Ingest Checkpoint Design

## Summary

This design changes normal ingest from a single late commit into a staged checkpoint pipeline so chunk data, chunk vectors, and extraction snapshots become durable before final graph materialization completes.

The target outcome is:

- document and chunk base data are visible before `PRIMARY_EXTRACTION`
- extraction results are durably persisted before formal graph writes
- graph materialization failures leave resumable checkpoints behind
- final graph writes remain the only step that marks the document `PROCESSED`
- existing public task APIs stay usable without requiring an immediate enum rename

## Goals

- Persist `documents`, `chunks`, and chunk vectors before graph extraction starts.
- Persist extraction snapshots and initial graph journals before final graph materialization starts.
- Reuse the existing `GraphMaterializationPipeline` as the resume and repair engine for failed finalization.
- Keep `DocumentStatus` coarse for now with `PROCESSING`, `FAILED`, and `PROCESSED`.
- Preserve task/event compatibility where possible while correcting misleading stage semantics.

## Non-Goals

- Do not introduce a new public `DocumentStatus` value in this iteration.
- Do not redesign the extraction model to stream per-token or per-LLM-call partial results.
- Do not rename the public task stage enums in this iteration.
- Do not change task execution from fail-fast batch behavior to partial-success task completion.
- Do not add batch orchestration for automatic document resume across many failed documents.

## Current Problem

Today `IndexingPipeline` computes the whole ingest result in memory and only persists it inside one late `commitComputedIngest(...)` transaction. That creates three practical issues:

- `PRIMARY_EXTRACTION` can run for minutes while no durable chunk or vector state exists.
- if graph persistence fails near the end, extraction truth is lost together with the failed transaction
- existing graph materialization recovery APIs exist, but the normal ingest path does not leave behind the snapshot and journal state they need

There is also a semantic bug in the current implementation:

- `persistDocumentGraphState(...)` writes `MERGED`, `SUCCEEDED`, and `MATERIALIZED` journal values even though the method currently runs inside the same commit that first writes the formal graph
- those values cannot be reused unchanged for a pre-materialization checkpoint

## Design Principles

- Separate checkpoint truth from final materialization truth.
- Make resume possible from durable storage, not from logs or in-memory leftovers.
- Keep the first implementation minimal by reusing existing snapshot and materialization types.
- Preserve current public API shape where the old names can still carry corrected semantics.
- Prefer explicit journal states over implicit inference from missing graph rows.

## Recommended Approach

This design adopts the staged approach previously discussed as "方案 C":

1. Base persistence checkpoint
2. Extraction snapshot checkpoint
3. Final graph materialization checkpoint

Public enum names remain unchanged for now, but their effective meaning becomes more precise.

## High-Level Flow

```text
IndexingPipeline
  |- CHUNKING
  |    `- persist documents + chunks + document_status(PROCESSING)
  |
  |- VECTOR_INDEXING
  |    `- persist chunk vectors
  |
  |- PRIMARY_EXTRACTION
  |    `- extract entities/relations in memory
  |
  |- GRAPH_ASSEMBLY
  |    `- assemble graph view
  |    `- persist document/chunk snapshots
  |    `- persist initial document/chunk journals
  |
  `- COMMITTING
       |- write formal graph
       |- write entity/relation vectors
       |- update journals to success or failure truth
       `- mark document PROCESSED
```

## Checkpoint Semantics

### 1. Base Persistence Checkpoint

When chunking finishes, the pipeline must durably persist:

- `documents`
- `chunks`
- `document_status = PROCESSING`

When chunk embedding finishes, the pipeline must durably persist:

- chunk vectors in `StorageSnapshots.CHUNK_NAMESPACE`

This checkpoint guarantees that, by the time `PRIMARY_EXTRACTION` starts, callers can already inspect the document shell, chunk rows, and chunk vectors.

### 2. Extraction Snapshot Checkpoint

After primary extraction and graph assembly complete, but before final graph writes, the pipeline must durably persist:

- `document_graph_snapshots`
- `chunk_graph_snapshots`
- initial `document_graph_journals`
- initial `chunk_graph_journals`

This checkpoint is the durable resume boundary.

It must not claim that graph materialization already succeeded.

### 3. Final Graph Materialization Checkpoint

Only the final phase may durably persist:

- formal graph entities and relations
- entity vectors
- relation vectors
- success-state graph journals
- `document_status = PROCESSED`

If this phase fails, earlier checkpoints remain intact and resumable.

## Journal Semantics

### Initial journal values

When the extraction snapshot checkpoint is created, the initial journal state should be:

- document journal
  - `status = NOT_STARTED`
  - `lastMode = AUTO`
  - `expectedEntityCount = assembled graph entity count`
  - `expectedRelationCount = assembled graph relation count`
  - `materializedEntityCount = 0`
  - `materializedRelationCount = 0`
  - `lastFailureStage = null`
  - `errorMessage = null`
- chunk journal
  - `mergeStatus = NOT_STARTED`
  - `graphStatus = NOT_MATERIALIZED`
  - `expectedEntityKeys = assembled chunk entity ids`
  - `expectedRelationKeys = assembled chunk relation ids`
  - `materializedEntityKeys = []`
  - `materializedRelationKeys = []`
  - `lastFailureStage = null`
  - `errorMessage = null`

These values describe "snapshot is ready, materialization has not started yet".

### Success journal values

After final materialization succeeds:

- document journal
  - `status = MERGED`
  - `materializedEntityCount = expectedEntityCount`
  - `materializedRelationCount = expectedRelationCount`
  - `lastFailureStage = FINALIZING`
  - `errorMessage = null`
- chunk journal
  - `mergeStatus = SUCCEEDED`
  - `graphStatus = MATERIALIZED`
  - `materializedEntityKeys = expectedEntityKeys`
  - `materializedRelationKeys = expectedRelationKeys`
  - `lastFailureStage = FINALIZING`
  - `errorMessage = null`

### Failure journal values

If final materialization fails after the extraction snapshot checkpoint is already durable:

- document status becomes `FAILED`
- the latest document journal should become:
  - `status = FAILED` if no formal graph rows were completed
  - `status = PARTIAL` if some formal graph rows were completed
- chunk journals should reflect the actual chunk merge truth:
  - `FAILED` or `PARTIAL` when some chunk-specific graph rows were written
  - otherwise remain `NOT_STARTED` / `NOT_MATERIALIZED`

The key rule is that failure updates journal truth but must not delete snapshots.

## Stage Meaning After This Change

- `CHUNKING`
  - chunk preparation plus base row persistence
- `VECTOR_INDEXING`
  - first occurrence means chunk vector persistence
  - second occurrence still means entity and relation vector persistence
- `PRIMARY_EXTRACTION`
  - extraction only
- `GRAPH_ASSEMBLY`
  - graph assembly plus snapshot and initial journal persistence
- `COMMITTING`
  - final graph materialization and final success-state commit

This keeps current enums stable while removing the old misleading interpretation that `COMMITTING` is the first time any document data becomes durable.

## Event Semantics

Current event names can stay, but their meaning must be clarified:

- `DOCUMENT_CHUNKED`
  - chunk rows are prepared and durably available
- `DOCUMENT_GRAPH_READY`
  - assembled graph exists and extraction snapshots have been durably persisted
- `DOCUMENT_VECTORS_READY`
  - final entity and relation vectors are ready
- `DOCUMENT_COMMITTED`
  - final graph materialization is complete and the document is fully processed

No new public event type is required in this iteration.

## Concurrency and Batch Failure Semantics

This design intentionally changes one old assumption.

Previously, if one document failed during concurrent ingest, another in-flight document could be prevented from leaving durable rows because all persistence was delayed until the end.

After this change, another document may already have durable base rows, chunk vectors, or extraction snapshots by the time a peer document fails.

That is acceptable and required for resume support.

The new invariant is:

- another document must not be incorrectly marked `PROCESSED`
- another document may remain `PROCESSING` or transition to `FAILED`
- if snapshots already exist, the document is eligible for later materialization resume

This means tests that currently enforce "no visible rows for other documents after concurrent failure" must be updated to the new checkpoint-based truth model.

## Resume Semantics

The resumed path relies on the existing `GraphMaterializationPipeline`:

- if snapshots exist and formal graph rows are missing, inspection should report `MISSING` with recommended mode `RESUME`
- `materialize(documentId, AUTO)` may internally choose `RESUME`
- chunk-level `resumeChunk(...)` and `repairChunk(...)` remain valid targeted recovery tools

The supported resume boundary for this iteration is:

- resume from the start of chunk extraction for a failed chunk extraction attempt
- resume from extraction snapshots for final graph materialization failure

The design does not attempt mid-request resume inside a single LLM call.

## Implementation Impact

### `IndexingPipeline`

Refactor the current late commit into separate write helpers:

- `persistBaseDocumentState(...)`
- `persistChunkVectors(...)`
- `persistExtractionSnapshotState(...)`
- `commitFinalMaterialization(...)`

The current `persistDocumentGraphState(...)` should be split because it mixes:

- snapshot persistence
- initial journal creation
- success-state finalization semantics

Those responsibilities need separate methods.

### `GraphMaterializationPipeline`

No public API redesign is required.

It should continue treating:

- snapshots + no formal graph rows as `MISSING`
- partial formal graph rows as `PARTIAL`
- successful completion as `MERGED`

This design relies on that behavior instead of replacing it.

### `TaskExecutionService`

Task projection logic can remain structurally the same, but event descriptions and tests must align with the new semantics:

- `DOCUMENT_GRAPH_READY` now means snapshot-ready, not already-materialized
- `DOCUMENT_COMMITTED` remains the only signal for full success

### Tests

The first required tests are:

- base rows and chunk vectors exist before `PRIMARY_EXTRACTION`
- snapshots survive an initial graph materialization failure
- later `GraphMaterializationPipeline.materialize(..., AUTO)` can converge the document to `MERGED`

Affected older tests must be updated where they encode the old "late all-or-nothing visibility" behavior.

## Risks

### Risk 1: misleading success journals

If initial snapshot persistence still writes `MERGED` or `MATERIALIZED`, inspection and resume logic will lie about the true state.

Mitigation:

- separate initial journal creation from final success journal updates

### Risk 2: concurrent failure semantics drift

Older tests and downstream assumptions may still expect no durable rows from non-failed documents.

Mitigation:

- update tests to assert `not PROCESSED` instead of `not visible`
- document the new checkpoint contract explicitly

### Risk 3: stage timing interpretation drift

Consumers may still read `GRAPH_ASSEMBLY` and `COMMITTING` using the old meaning.

Mitigation:

- update stage messages
- update task/event tests
- defer enum renaming to a later compatibility-focused change

## Rollout Plan

Implementation should proceed in this order:

1. Make the new resumable-ingest tests pass.
2. Split `IndexingPipeline` persistence helpers.
3. Correct initial journal semantics.
4. Update old concurrency and task-event tests to the new checkpoint contract.
5. Run focused regression tests for `IndexingPipeline`, `LightRagBuilder`, `LightRagTaskApi`, and `GraphMaterializationPipeline`.

## Decision

Adopt staged checkpoint ingest now, keep current public enum names for one iteration, and use durable snapshots plus honest journals as the contract for resumable graph materialization.
