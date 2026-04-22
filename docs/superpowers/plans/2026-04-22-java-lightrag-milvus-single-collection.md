# Java LightRAG Milvus Single Collection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Milvus from per-workspace/per-namespace collections to one shared physical collection while preserving logical `chunks` / `entities` / `relations` behavior in the Java SDK.

**Architecture:** Keep the public `VectorStore` and `HybridVectorStore` namespace contract unchanged. Change only the Milvus adapter boundary so every row carries `workspace_id`, `record_type`, `vector_id`, and a deterministic technical `pk_id`, then scope all search/list/delete operations by `workspace_id + record_type`.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Milvus Java v2 SDK, existing LightRAG storage abstractions

---

### Task 1: Lock Down Shared-Collection Contract with Failing Tests

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterIntegrationTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusVectorStoreTest.java` or create it if missing

- [ ] **Step 1: Add schema contract tests for the new shared collection fields**

Test for:
- shared schema contains `pk_id`, `vector_id`, `workspace_id`, `record_type`
- relation metadata fields still exist in the unified schema
- `vector_id` / `workspace_id` use the large varchar budget

- [ ] **Step 2: Add deterministic ID/filter tests**

Test for:
- one shared collection name derived from `collectionPrefix`
- technical `pk_id` is stable for the same `workspace_id + record_type + vector_id`
- same `vector_id` in different workspaces or namespaces produces different `pk_id`

- [ ] **Step 3: Add read/write semantics tests against the store layer**

Test for:
- saving `chunks`, `entities`, and `relations` no longer creates distinct physical collection names
- list/search still behave per logical namespace
- deleteNamespace only clears one `workspace_id + record_type` slice

- [ ] **Step 4: Run the failing Milvus-focused tests**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest" \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterIntegrationTest"
```

Expected:
- FAIL because the current adapter still assumes one collection per workspace/namespace and has no shared-collection metadata fields

### Task 2: Implement Shared Collection Naming and Unified Schema

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`

- [ ] **Step 1: Add shared collection name resolution**

Implement:
- a new config helper that resolves one shared physical collection name from `collectionPrefix`
- current namespace-based collection-name derivation remains available only if needed for compatibility inside tests during transition

- [ ] **Step 2: Extend Milvus request/row contracts**

Add to the adapter contract:
- `pk_id`
- `vector_id`
- `workspace_id`
- `record_type`
- row-level filter support for search/list/delete requests

- [ ] **Step 3: Replace split schema generation with the unified schema**

Implement:
- unified field list
- `pk_id` as primary key
- `vector_id` as returned business identifier
- relation metadata fields present for all rows

- [ ] **Step 4: Re-run Milvus unit tests**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest"
```

Expected:
- PASS for schema/name/ID contract tests

### Task 3: Implement Workspace + Record-Type Scoped Reads/Writes

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java`

- [ ] **Step 1: Make all writes target the shared collection**

Implement:
- compute `workspace_id` from the store instance
- compute `record_type` from `namespace`
- compute deterministic `pk_id`
- upsert rows into the shared collection only

- [ ] **Step 2: Make all reads/deletes apply row filters**

Implement:
- semantic search filter
- keyword search filter
- hybrid search filter
- list filter
- deleteNamespace filter
- flushNamespaces collapsing to one physical collection flush

- [ ] **Step 3: Preserve current namespace-based restore behavior**

Implement:
- captureSnapshot remains `chunks/entities/relations`
- restore deletes only one logical slice at a time
- returned search IDs remain `vector_id`, not `pk_id`

- [ ] **Step 4: Run Milvus-focused regression tests**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest" \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterIntegrationTest"
```

Expected:
- PASS for shared-collection Milvus behavior

### Task 4: Verify Query and Provider Integration Paths

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/GlobalQueryStrategyTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProviderTest.java`

- [ ] **Step 1: Add/adjust regression tests for logical namespace behavior**

Test for:
- local query still resolves entity vectors
- global query still resolves relation vectors
- chunk retrieval remains scoped to chunk vectors only

- [ ] **Step 2: Add provider-level restore/rebuild regression coverage**

Test for:
- mixed providers still restore vectors through logical namespaces
- same business ID across namespaces/workspaces cannot collide in Milvus

- [ ] **Step 3: Run the targeted integration/regression suite**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.query.LocalQueryStrategyTest" \
  --tests "io.github.lightrag.query.GlobalQueryStrategyTest" \
  --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" \
  --tests "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProviderTest"
```

Expected:
- PASS with no namespace semantic regressions

### Task 5: Final Verification and Commit

**Files:**
- Modify: any files touched above

- [ ] **Step 1: Run the final verification suite**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest" \
  --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterIntegrationTest" \
  --tests "io.github.lightrag.query.LocalQueryStrategyTest" \
  --tests "io.github.lightrag.query.GlobalQueryStrategyTest" \
  --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" \
  --tests "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProviderTest"
```

Expected:
- PASS

- [ ] **Step 2: Inspect git diff**

Run:
```bash
git status --short
git diff --stat
```

Expected:
- only Milvus single-collection implementation and test changes are present

- [ ] **Step 3: Commit implementation**

Run:
```bash
git add lightrag-core/src/main/java/io/github/lightrag/storage/milvus \
        lightrag-core/src/test/java/io/github/lightrag/storage/milvus \
        lightrag-core/src/test/java/io/github/lightrag/query \
        lightrag-core/src/test/java/io/github/lightrag/storage/mysql \
        lightrag-core/src/test/java/io/github/lightrag/storage/postgres \
        docs/superpowers/plans/2026-04-22-java-lightrag-milvus-single-collection.md
git commit -m "feat: unify milvus vectors into one shared collection"
```

Expected:
- one feature commit containing the single-collection implementation and its plan
