# Java LightRAG Document Status Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: persistent document processing status tracking.

The upstream LightRAG project exposes document-status visibility through a dedicated status store and uses it to track ingestion lifecycle, failures, and deletion cleanup.

The Java SDK currently ingests documents directly into document, chunk, graph, and vector stores, but it does not persist per-document processing state. This phase adds document-status storage and query APIs before considering more ambitious async orchestration.

## Goals

- Add persistent per-document processing status to the Java SDK.
- Keep status updates consistent with successful or failed ingest/delete operations.
- Support all bundled storage providers without widening the existing atomicity boundary beyond current provider capabilities.
- Expose a typed Java API for querying document status.

## Non-Goals

- Do not add background workers, queues, or eventual-consistency infrastructure in this phase.
- Do not redesign the current synchronous `ingest(...)` API into an async scheduler.
- Do not implement chunk-tracking stores beyond what can be summarized in document status.
- Do not add partial-document retry orchestration in this phase.

## Upstream Behavior To Align

According to upstream behavior, document processing has explicit status tracking for:

- pending / processing work
- successful completion
- failure details
- deletion cleanup

The Java SDK cannot match the async internals directly without adding workers and scheduling. The closest visible behavior in this phase is:

- create a status entry when a document starts processing
- mark it `PROCESSED` on successful ingest
- mark it `FAILED` with an error message on ingest failure
- remove status entries when a document is deleted

## Java API Shape

Add typed APIs to `LightRag`:

- `DocumentProcessingStatus getDocumentStatus(String documentId)`
- `List<DocumentProcessingStatus> listDocumentStatuses()`

Add a public enum:

- `DocumentStatus`

And a public result type:

- `DocumentProcessingStatus`

Fields should include:

- `documentId`
- `status`
- `summary`
- `errorMessage`

The initial Java phase should avoid timestamps or queue metadata unless the storage layer needs them for correctness.

## Storage Design

Add a dedicated SPI:

- `DocumentStatusStore`

Responsibilities:

- save or upsert one status record
- load one status record
- list all status records
- delete one status record

Add implementations for:

- in-memory storage
- PostgreSQL storage

`PostgresNeo4jStorageProvider` should delegate status storage to PostgreSQL, matching the existing “PostgreSQL is source of truth” design.

## Status Model

Use a small, explicit enum:

- `PENDING`
- `PROCESSING`
- `PROCESSED`
- `FAILED`

The public enum keeps `PENDING` for parity with the broader LightRAG status vocabulary, but the current synchronous Java implementation does not enqueue work before processing.

Because Java ingestion is synchronous, the observable successful flow in this phase moves:

- `PROCESSING` -> `PROCESSED`

A failed flow moves:

- `PROCESSING` -> `FAILED`

The status summary should be small and human-readable. The initial Java phase should store:

- success summary such as processed chunk count
- failure summary such as the top-level exception message

## Ingest Semantics

For each document in `ingest(List<Document> documents)`:

1. mark status `PROCESSING` before extraction/indexing starts
2. on success, mark status `PROCESSED`
3. on failure, mark status `FAILED` with an error message

The Java SDK already ingests synchronously. This phase should process documents one by one instead of one all-or-nothing batch so per-document status is observable and preserved on later failures.

This is an intentional behavioral shift toward upstream visibility: earlier successfully processed documents remain available and retain `PROCESSED` status even if a later document in the same call fails.

## Delete Semantics

`deleteByDocumentId(docId)` should:

- remove the document status entry when the document is actually deleted
- keep status unchanged when the document does not exist

If deletion rebuild fails and the original snapshot is restored, the original status set must also be restored.

## Failure And Atomicity Semantics

Within one document’s ingest attempt:

- status writes and content writes should be applied atomically through the provider’s existing `writeAtomically(...)` or snapshot restore path when possible

Snapshot capture and restore should include document statuses so autosave and rollback preserve the same visible state.

Across a multi-document `ingest(...)` call:

- each document becomes its own atomic unit
- later document failures must not roll back earlier successful documents

This differs from the current Java batch-ingest behavior, but it better matches the visible upstream notion of persistent per-document status.

## Testing Strategy

Required coverage:

- successful ingest writes `PROCESSED` status
- failed ingest writes `FAILED` status and preserves earlier successful documents
- listing statuses returns deterministic order
- deleting a document removes its status
- statuses survive snapshot autosave when configured
- PostgreSQL provider persists statuses
- PostgreSQL+Neo4j provider persists statuses through PostgreSQL

Verification should include targeted tests plus full `./gradlew test`.
