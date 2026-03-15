# Java LightRAG Graph Management Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned manual entity and relation create/edit APIs to the Java LightRAG SDK.

**Architecture:** `LightRag` will delegate graph-management operations to a new `GraphManagementPipeline`. Pure create flows will use `AtomicStorageProvider.writeAtomically(...)` so they append graph/vector state without whole-snapshot restore, while edit flows will still capture snapshots, mutate entity/relation state in memory, regenerate affected vectors, and restore the replacement snapshot. The public API will expose typed request/result records so Java callers get explicit validation and rename semantics without using dynamic maps.

**Tech Stack:** Java 17, existing `AtomicStorageProvider` SPI, in-memory storage, PostgreSQL storage, JUnit 5, AssertJ

---

## Chunk 1: Public API And Create Flows

### Task 1: Add failing create-flow end-to-end tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing entity-create test**

Add an end-to-end test that calls `createEntity(...)` for `Alice` and asserts:

- the returned `GraphEntity` matches the stored entity
- entity `entity:alice` exists
- entity metadata matches the request
- entity vector `entity:alice` exists
- no documents or chunks were created

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.createsEntityWithoutDocumentsOrChunks`
Expected: FAIL because the create API does not exist yet.

- [ ] **Step 3: Write the failing relation-create test**

Add an end-to-end test that creates two entities, then calls `createRelation(...)` and asserts:

- the returned `GraphRelation` matches the stored relation
- relation record exists with the expected relation ID
- relation metadata matches the request
- relation vector exists
- entity vectors remain

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.createsRelationBetweenExistingEntities`
Expected: FAIL because the create relation API does not exist yet.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG graph management create flows"
```

### Task 2: Add create request/result records and pipeline support

**Files:**
- Create: `src/main/java/io/github/lightragjava/api/CreateEntityRequest.java`
- Create: `src/main/java/io/github/lightragjava/api/CreateRelationRequest.java`
- Create: `src/main/java/io/github/lightragjava/api/GraphEntity.java`
- Create: `src/main/java/io/github/lightragjava/api/GraphRelation.java`
- Create: `src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`

- [ ] **Step 1: Add public request/result records**

Implement immutable Java API types for entity and relation create flows. Follow the existing `QueryRequest` pattern for optional fields.

- [ ] **Step 2: Extract reusable vector-generation helpers**

Refactor `IndexingPipeline` so graph-management code can regenerate entity and relation vectors without duplicating embedding logic.

- [ ] **Step 3: Implement createEntity and createRelation**

Add `GraphManagementPipeline` methods that:

- capture the current snapshot
- validate uniqueness and endpoint existence
- validate non-blank names and relation types
- validate finite relation weights
- normalize aliases for trim, deduplication, and self-name removal
- fail on ambiguous alias-based entity resolution
- reject any create that reuses an existing external lookup string across entity names and aliases
- append entity/relation records
- regenerate only the affected entity/relation vectors
- persist create flows through `writeAtomically(...)`

- [ ] **Step 4: Expose create methods from `LightRag`**

Add:

- `createEntity(CreateEntityRequest request)`
- `createRelation(CreateRelationRequest request)`

Make both methods return `GraphEntity` / `GraphRelation` and assert those return values in the create-flow tests.

- [ ] **Step 5: Run targeted create-flow tests**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.createsEntityWithoutDocumentsOrChunks --tests io.github.lightragjava.E2ELightRagTest.createsRelationBetweenExistingEntities`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/CreateEntityRequest.java src/main/java/io/github/lightragjava/api/CreateRelationRequest.java src/main/java/io/github/lightragjava/api/GraphEntity.java src/main/java/io/github/lightragjava/api/GraphRelation.java src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG graph management create flows"
```

## Chunk 2: Edit Flows And Rename Migration

### Task 3: Add failing edit-flow tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing entity-edit test**

Add an end-to-end test that:

- creates `Alice` and `Bob`
- creates `Alice works_with Bob`
- renames `Bob` to `Robert`
- asserts the entity ID becomes `entity:robert`
- asserts the old display name is retained as an alias
- asserts the relation ID becomes `relation:entity:alice|works_with|entity:robert`
- asserts the old relation/entity vectors are replaced

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.editsEntityAndMigratesRelationsOnRename`
Expected: FAIL because edit flow is not implemented yet.

- [ ] **Step 3: Write the failing relation-edit test**

Add an end-to-end test that edits a relation's type, description, and weight and then asserts:

- the old relation ID is gone
- the new relation ID exists
- the new metadata is stored
- the relation vector was refreshed

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.editsRelationAndRefreshesRelationVector`
Expected: FAIL because edit relation is not implemented yet.

- [ ] **Step 5: Write the failing metadata-only entity edit test**

Add an end-to-end test that edits an entity description or aliases without renaming it and asserts:

- the entity ID stays stable
- the stored metadata changes
- the entity vector is refreshed

- [ ] **Step 6: Run the targeted metadata-edit test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.editsEntityMetadataAndRefreshesEntityVector`
Expected: FAIL because metadata-only edit is not implemented yet.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG graph management edit flows"
```

### Task 4: Implement edit request/result support and validation

**Files:**
- Create: `src/main/java/io/github/lightragjava/api/EditEntityRequest.java`
- Create: `src/main/java/io/github/lightragjava/api/EditRelationRequest.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add edit request records**

Implement typed edit request records with optional update fields for rename, aliases, description, type, and weight.
`EditRelationRequest` must include a required `currentRelationType` plus an optional `newRelationType` so the existing relation is unambiguous before optional type replacement.

- [ ] **Step 2: Implement editEntity**

Support metadata updates plus rename migration:

- resolve by exact normalized name first, then by alias only when the alias match is unique
- validate rename collisions
- refresh the entity vector even when only metadata changes and the entity ID is unchanged
- create the replacement entity record
- rewrite all affected relations to use the new entity ID
- regenerate vectors for the renamed entity and affected relations

- [ ] **Step 3: Implement editRelation**

Support metadata updates for:

- relation type
- description
- weight

Resolve endpoints by exact normalized name first, then by alias only when the alias match is unique.
Locate the existing relation by resolved endpoints plus `currentRelationType`.
If the relation type changes, regenerate the relation ID and reject collisions.

- [ ] **Step 4: Expose edit methods from `LightRag`**

Add:

- `editEntity(EditEntityRequest request)`
- `editRelation(EditRelationRequest request)`

- [ ] **Step 5: Add duplicate/missing validation coverage**

Add tests for:

- duplicate entity create rejection
- duplicate relation create rejection
- invalid entity-name / alias / relation-type / weight validation
- edit of missing entity or relation rejection
- autosnapshot persistence after graph-management mutation

- [ ] **Step 6: Add provider-backed graph-management coverage**

Add one PostgreSQL and one PostgreSQL+Neo4j end-to-end test that exercises at least:

- manual entity creation
- manual relation creation
- entity rename or relation edit

The goal is to verify graph-management snapshot restore behavior beyond the in-memory provider.

- [ ] **Step 7: Run targeted graph-management tests**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.createsEntityWithoutDocumentsOrChunks --tests io.github.lightragjava.E2ELightRagTest.createsRelationBetweenExistingEntities --tests io.github.lightragjava.E2ELightRagTest.editsEntityAndMigratesRelationsOnRename --tests io.github.lightragjava.E2ELightRagTest.editsRelationAndRefreshesRelationVector --tests io.github.lightragjava.E2ELightRagTest.editsEntityMetadataAndRefreshesEntityVector --tests io.github.lightragjava.E2ELightRagTest.rejectsDuplicateGraphManagementCreatesAndMissingEdits --tests io.github.lightragjava.E2ELightRagTest.graphManagementPersistsSnapshotWhenConfigured`
Expected: PASS.

- [ ] **Step 8: Run provider-backed graph-management tests**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresProviderSupportsGraphManagement`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest.postgresNeo4jProviderSupportsGraphManagement`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/EditEntityRequest.java src/main/java/io/github/lightragjava/api/EditRelationRequest.java src/main/java/io/github/lightragjava/api/LightRag.java src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG graph management edit flows"
```

## Chunk 3: Docs And Final Verification

### Task 5: Align docs and verify full suite

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-graph-management-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-graph-management.md`

- [ ] **Step 1: Document the new API surface**

Add a README example for:

- creating an entity
- creating a relation
- renaming an entity
- editing a relation

Call out that Java relation operations require explicit `relationType`.

- [ ] **Step 2: Keep design and plan aligned with implementation**

Update docs if implementation differs from the initial graph-management design.

- [ ] **Step 3: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Inspect git diff**

Run: `git status --short`
Expected: only graph-management-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-15-java-lightrag-graph-management-design.md docs/superpowers/plans/2026-03-15-java-lightrag-graph-management.md src/main/java/io/github/lightragjava/api src/main/java/io/github/lightragjava/indexing/GraphManagementPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "docs: finalize LightRAG graph management alignment"
```
