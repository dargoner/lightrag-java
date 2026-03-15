# Java LightRAG Document Status Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persistent document processing status tracking to the Java LightRAG SDK.

**Architecture:** Add a new `DocumentStatusStore` SPI with in-memory and PostgreSQL implementations, expose typed status query APIs from `LightRag`, and refactor synchronous ingest to process documents one at a time so each document gets durable `PROCESSING`/`PROCESSED`/`FAILED` lifecycle updates. `DocumentStatus` still includes `PENDING` for API parity, but synchronous ingest does not currently surface it. `deleteByDocumentId(...)` will remove status entries as part of its existing rebuild workflow.

**Tech Stack:** Java 17, existing `AtomicStorageProvider` SPI, in-memory storage, PostgreSQL storage, PostgreSQL+Neo4j storage, JUnit 5, AssertJ

---

## Chunk 1: API And Store Surface

### Task 1: Add failing document-status API tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing successful-ingest status test**

Add a test that ingests one document and asserts:

- `getDocumentStatus("doc-1")` returns `PROCESSED`
- `listDocumentStatuses()` returns one record
- the success summary is non-blank

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.ingestPersistsProcessedDocumentStatus`
Expected: FAIL because the status API does not exist yet.

- [ ] **Step 3: Write the failing failed-ingest status test**

Add a test that ingests two documents where the second fails during extraction and asserts:

- the first document remains ingested with `PROCESSED` status
- the second document has `FAILED` status with an error message

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.failedIngestPersistsFailedDocumentStatus`
Expected: FAIL because per-document status tracking does not exist yet.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG document status APIs"
```

### Task 2: Add status model, store SPI, and public API

**Files:**
- Create: `src/main/java/io/github/lightragjava/api/DocumentStatus.java`
- Create: `src/main/java/io/github/lightragjava/api/DocumentProcessingStatus.java`
- Create: `src/main/java/io/github/lightragjava/storage/DocumentStatusStore.java`
- Modify: `src/main/java/io/github/lightragjava/storage/StorageProvider.java`
- Modify: `src/main/java/io/github/lightragjava/storage/AtomicStorageProvider.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `src/main/java/io/github/lightragjava/config/LightRagConfig.java`

- [ ] **Step 1: Add public status types**

Implement `DocumentStatus` and `DocumentProcessingStatus`.

- [ ] **Step 2: Add the `DocumentStatusStore` SPI**

Include:

- `save(StatusRecord record)`
- `load(String documentId)`
- `list()`
- `delete(String documentId)`

- [ ] **Step 3: Wire status storage through provider interfaces and builder validation**

Make `StorageProvider` expose `documentStatusStore()` and require it during `LightRagBuilder.build()`.

- [ ] **Step 4: Expose status query APIs from `LightRag`**

Add:

- `getDocumentStatus(String documentId)`
- `listDocumentStatuses()`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/DocumentStatus.java src/main/java/io/github/lightragjava/api/DocumentProcessingStatus.java src/main/java/io/github/lightragjava/storage/DocumentStatusStore.java src/main/java/io/github/lightragjava/storage/StorageProvider.java src/main/java/io/github/lightragjava/storage/AtomicStorageProvider.java src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/api/LightRagBuilder.java src/main/java/io/github/lightragjava/config/LightRagConfig.java
git commit -m "feat: add LightRAG document status API surface"
```

## Chunk 2: In-Memory Flow And Delete Cleanup

### Task 3: Implement in-memory status storage and ingest/delete integration

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryDocumentStatusStore.java`
- Modify: `src/main/java/io/github/lightragjava/storage/InMemoryStorageProvider.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Implement the in-memory store**

Add deterministic snapshot/restore support comparable to other in-memory stores.

- [ ] **Step 2: Refactor ingest to process one document at a time**

For each document:

- set `PROCESSING`
- attempt ingest
- on success set `PROCESSED`
- on failure set `FAILED`

- [ ] **Step 3: Update document deletion cleanup**

Make `deleteByDocumentId(...)` remove the document status entry when deletion succeeds and restore the previous status set if rebuild fails.

- [ ] **Step 4: Run targeted in-memory status tests**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.ingestPersistsProcessedDocumentStatus`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.failedIngestPersistsFailedDocumentStatus`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deleteByDocumentRemovesDocumentStatus`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/memory/InMemoryDocumentStatusStore.java src/main/java/io/github/lightragjava/storage/InMemoryStorageProvider.java src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add in-memory LightRAG document status tracking"
```

## Chunk 3: PostgreSQL, Neo4j Delegation, And Docs

### Task 4: Add PostgreSQL-backed status storage and provider coverage

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresDocumentStatusStore.java`
- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java`
- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Modify: `src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Add PostgreSQL schema and store implementation**

Create a `document_status` table with a stable document-id primary key and fields for status, summary, and error message.

- [ ] **Step 2: Wire PostgreSQL and PostgreSQL+Neo4j providers**

Use PostgreSQL as the source of truth for document status in both providers.

- [ ] **Step 3: Add provider-backed end-to-end coverage**

Add tests for:

- processed status persistence in PostgreSQL
- failed status persistence in PostgreSQL
- processed status persistence in PostgreSQL+Neo4j

- [ ] **Step 4: Run targeted provider status tests**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresProviderPersistsDocumentStatus`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresProviderPersistsFailedDocumentStatus`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresNeo4jProviderPersistsDocumentStatus`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresDocumentStatusStore.java src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java src/test/java/io/github/lightragjava/E2ELightRagTest.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "feat: add PostgreSQL LightRAG document status storage"
```

### Task 5: Document and verify document-status support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-document-status-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-document-status.md`

- [ ] **Step 1: Document the status APIs**

Add README examples for:

- querying one document status
- listing all document statuses
- expected processed/failed behavior for synchronous ingest

- [ ] **Step 2: Keep spec and plan aligned with the final implementation**

Update docs if implementation differs from the initial design.

- [ ] **Step 3: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run: `git status --short`
Expected: only document-status-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-15-java-lightrag-document-status-design.md docs/superpowers/plans/2026-03-15-java-lightrag-document-status.md
git commit -m "docs: finalize LightRAG document status alignment"
```
