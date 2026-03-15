# Delete Alignment Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned delete operations to the Java LightRAG SDK for entity, relation, and document deletion.

**Architecture:** `LightRag` will delegate delete operations to a new `DeletionPipeline` that captures provider snapshots, computes replacement state in memory, and applies it through `AtomicStorageProvider.restore(...)`. Entity and relation deletes mutate the current snapshot directly; document delete clears storage and rebuilds all remaining documents through the existing indexing pipeline components so graph and vector state stay coherent.

**Tech Stack:** Java 17, existing `AtomicStorageProvider` SPI, in-memory storage, PostgreSQL storage, JUnit 5, AssertJ

---

## Chunk 1: Public Delete API And Snapshot Helpers

### Task 1: Add failing delete API tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing entity-delete test**

Add an end-to-end test that ingests one document containing `Alice works with Bob`, calls `deleteByEntity("Alice")`, and then asserts:

- entity `Alice` is gone from storage
- connected relation is gone
- entity/relation vectors tied to Alice are gone

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deletesEntityAndConnectedRelations`
Expected: FAIL because `LightRag` has no delete API yet.

- [ ] **Step 3: Write the failing relation-delete test**

Add an end-to-end test that ingests one relation, calls `deleteByRelation("Alice", "Bob")`, and then asserts:

- the relation is removed
- both entities remain

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deletesRelationButPreservesEntities`
Expected: FAIL because `LightRag` has no delete API yet.

- [ ] **Step 5: Write the failing document-delete test**

Add an end-to-end test that ingests:

- one document about `Alice works with Bob`
- one document about `Bob works with Carol`

Then call `deleteByDocumentId("doc-1")` and assert:

- `doc-1` and its chunks are removed
- `doc-2` remains
- `Bob` and `Carol` knowledge remains after rebuild

- [ ] **Step 6: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deletesDocumentAndRebuildsRemainingKnowledge`
Expected: FAIL because document delete is not implemented.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG delete alignment"
```

### Task 2: Add delete coordinator and public API

**Files:**
- Create: `src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`

- [ ] **Step 1: Implement snapshot capture and autosave helpers**

Refactor `IndexingPipeline` so snapshot capture/autosave logic can be reused by deletion without copy-pasting provider reads again.

- [ ] **Step 2: Implement entity and relation delete**

Add `DeletionPipeline` methods that:

- capture the current snapshot
- resolve entities by name/alias
- drop entity/relation records and matching vectors
- restore the replacement snapshot

- [ ] **Step 3: Expose delete methods from `LightRag`**

Add:

- `deleteByEntity(String entityName)`
- `deleteByRelation(String sourceEntityName, String targetEntityName)`
- `deleteByDocumentId(String documentId)`

- [ ] **Step 4: Run targeted end-to-end tests**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deletesEntityAndConnectedRelations --tests io.github.lightragjava.E2ELightRagTest.deletesRelationButPreservesEntities`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG entity and relation deletion"
```

## Chunk 2: Document Delete Rebuild And Rollback

### Task 3: Implement document-delete rebuild and failure recovery

**Files:**
- Modify: `src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing rollback test**

Add a test using a failing model or pipeline dependency so `deleteByDocumentId(...)` clears storage, rebuild fails, and the original snapshot is restored.

- [ ] **Step 2: Run the targeted rollback test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.restoresOriginalStateWhenDocumentDeleteRebuildFails`
Expected: FAIL because rollback is not implemented yet.

- [ ] **Step 3: Implement document-delete rebuild**

Make `deleteByDocumentId(...)`:

- capture full pre-delete snapshot
- compute remaining documents
- clear storage with `restore(emptySnapshot)`
- re-run the existing indexing flow for remaining documents
- autosave snapshot after success

- [ ] **Step 4: Implement rollback on rebuild failure**

If rebuild fails, restore the captured pre-delete snapshot and surface the original failure.

- [ ] **Step 5: Run targeted delete tests**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.deletesDocumentAndRebuildsRemainingKnowledge --tests io.github.lightragjava.E2ELightRagTest.restoresOriginalStateWhenDocumentDeleteRebuildFails`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG document deletion rebuild"
```

## Chunk 3: Final Alignment And Verification

### Task 4: Align docs and verify full suite

**Files:**
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-delete-alignment-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-delete-alignment.md`

- [ ] **Step 1: Update docs if implementation differs**

Keep the design and plan aligned with the final delete API and rebuild strategy.

- [ ] **Step 2: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Inspect git diff**

Run: `git status --short`
Expected: only delete-alignment-related files changed.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-03-15-java-lightrag-delete-alignment-design.md docs/superpowers/plans/2026-03-15-java-lightrag-delete-alignment.md src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "docs: finalize LightRAG delete alignment plan"
```
