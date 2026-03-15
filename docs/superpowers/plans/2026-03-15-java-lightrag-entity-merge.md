# Java LightRAG Entity Merge Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned entity merge support to the Java LightRAG SDK.

**Architecture:** `LightRag` will delegate entity merges to `GraphManagementPipeline`, which will capture the current snapshot, compute a replacement target entity plus rewritten relations in memory, regenerate only affected vectors, and restore the resulting snapshot. The public API will use a typed request object instead of a dynamic map while still covering upstream `target_entity_data` semantics through explicit override fields.

**Tech Stack:** Java 17, existing `AtomicStorageProvider` SPI, in-memory storage, PostgreSQL storage, PostgreSQL+Neo4j storage, JUnit 5, AssertJ

---

## Chunk 1: Public API And Core In-Memory Merge Flow

### Task 1: Add failing entity-merge end-to-end tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing basic merge test**

Add an end-to-end test that:

- creates `Alice`, `Bob`, and `Robert`
- creates relations touching `Bob`
- merges `Bob` into `Robert`
- asserts `entity:bob` is gone
- asserts the returned `GraphEntity` is the merged target
- asserts affected relation IDs now point at `entity:robert`

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergesEntitiesAndRedirectsRelations`
Expected: FAIL because the merge API does not exist yet.

- [ ] **Step 3: Write the failing duplicate-relation and self-loop merge test**

Add an end-to-end test that merges an entity whose rewritten edges would:

- collide with an existing relation ID
- create a self-loop

Assert duplicate relations fold into one record and self-loops are removed.

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergeEntitiesFoldsDuplicateRelationsAndDropsSelfLoops`
Expected: FAIL because merge is not implemented yet.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG entity merge flows"
```

### Task 2: Add merge request support and pipeline implementation

**Files:**
- Create: `src/main/java/io/github/lightragjava/api/MergeEntitiesRequest.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add the public merge request record**

Implement an immutable Java request type that includes:

- `sourceEntityNames`
- `targetEntityName`
- optional `targetType`
- optional `targetDescription`
- optional `targetAliases`

Apply the same trim, dedupe, and validation style used by current graph-management request records.

- [ ] **Step 2: Implement `mergeEntities(...)` in `GraphManagementPipeline`**

Add a merge path that:

- resolves target and sources deterministically
- rejects duplicate resolved sources
- rejects target-in-source-set
- computes merged target metadata and sourceChunkIds
- rewrites source relations to the target
- removes self-loops
- folds duplicate rewritten relations
- refreshes the target entity vector
- refreshes all surviving affected relation vectors
- restores the replacement snapshot

- [ ] **Step 3: Expose `mergeEntities(...)` from `LightRag`**

Add:

- `mergeEntities(MergeEntitiesRequest request)`

Return `GraphEntity` and assert the returned DTO in tests.

- [ ] **Step 4: Run targeted entity-merge tests**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergesEntitiesAndRedirectsRelations --tests io.github.lightragjava.E2ELightRagTest.mergeEntitiesFoldsDuplicateRelationsAndDropsSelfLoops`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/MergeEntitiesRequest.java src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG entity merge flow"
```

## Chunk 2: Validation, Provider Coverage, And Docs

### Task 3: Add merge validation and provider-backed coverage

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write failing validation tests**

Add end-to-end coverage for:

- ambiguous alias source resolution
- duplicate source references after resolution
- target included in sources
- missing source or missing target rejection
- explicit target metadata overrides
- autosnapshot persistence after merge

- [ ] **Step 2: Run the targeted validation tests to verify they fail**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.rejectsInvalidEntityMergeRequests --tests io.github.lightragjava.E2ELightRagTest.mergeEntitiesPersistsSnapshotWhenConfigured`
Expected: FAIL until the validation and snapshot behavior are complete.

- [ ] **Step 3: Add provider-backed entity-merge tests**

Add one PostgreSQL and one PostgreSQL+Neo4j end-to-end test that exercises at least:

- creating source and target entities
- merging one source into the target
- validating final entity, relation, and vector state

- [ ] **Step 4: Run provider-backed merge tests**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresProviderSupportsEntityMerge`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresNeo4jProviderSupportsEntityMerge`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG entity merge validation"
```

### Task 4: Document entity merge and run full verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-entity-merge-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-entity-merge.md`

- [ ] **Step 1: Document the new API surface**

Add a README example for `mergeEntities(...)`, including:

- one source entity
- one target entity
- optional metadata overrides
- expected relation redirection behavior

- [ ] **Step 2: Keep design and plan aligned with implementation**

Update the spec and plan if implementation differs in any meaningful way from the initial design.

- [ ] **Step 3: Run targeted merge suite**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergesEntitiesAndRedirectsRelations`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergeEntitiesFoldsDuplicateRelationsAndDropsSelfLoops`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.rejectsInvalidEntityMergeRequests`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.mergeEntitiesPersistsSnapshotWhenConfigured`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresProviderSupportsEntityMerge`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresNeo4jProviderSupportsEntityMerge`

Expected: PASS.

- [ ] **Step 4: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Inspect git diff**

Run: `git status --short`
Expected: only entity-merge-related files changed.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-15-java-lightrag-entity-merge-design.md docs/superpowers/plans/2026-03-15-java-lightrag-entity-merge.md src/main/java/io/github/lightragjava/api/MergeEntitiesRequest.java src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "docs: finalize LightRAG entity merge alignment"
```
