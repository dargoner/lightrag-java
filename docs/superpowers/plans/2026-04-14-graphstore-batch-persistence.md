# GraphStore Batch Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add batch-oriented graph persistence APIs and use them in the indexing/materialization pipelines so large graph merges stop doing one-record-at-a-time graph round trips.

**Architecture:** Extend `GraphStore` with default batch methods that preserve current semantics, then override them in PostgreSQL and Neo4j with backend-native batching. Keep transactional behavior unchanged by teaching the storage wrappers, `StorageCoordinator` staging layer, and the MySQL+Milvus+Neo4j combination provider adapters to carry batch writes through the existing atomic commit path, then switch `IndexingPipeline` and `GraphMaterializationPipeline` to batch load and batch save merged graph data.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, JDBC batch writes, Neo4j `UNWIND`, existing `AtomicStorageProvider` / `StorageCoordinator` abstractions, PostgreSQL and Neo4j Testcontainers.

---

## File Structure

**Modify**

- `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryGraphStoreTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProviderTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java`

**Create**

- `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java`

**Reference During Implementation**

- `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStorageAdapter.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresRelationalStorageAdapter.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java`

## Task 1: Extend GraphStore With Safe Default Batch Methods

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryGraphStoreTest.java`

- [ ] **Step 1: Write failing tests for default batch semantics**

```java
@Test
void defaultBatchEntitySavePreservesLastWriteWins() {
    var store = new InMemoryGraphStore();
    var first = new GraphStore.EntityRecord("entity-1", "Alice", "person", "v1", List.of("A"), List.of("chunk-1"));
    var second = new GraphStore.EntityRecord("entity-1", "Alice", "person", "v2", List.of("A"), List.of("chunk-2"));

    store.saveEntities(List.of(first, second));

    assertThat(store.loadEntity("entity-1")).contains(second);
}

@Test
void defaultBatchLoadReturnsRecordsInRequestedOrderAndSkipsMissingIds() {
    var store = new InMemoryGraphStore();
    var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of(), List.of("chunk-1"));
    var bob = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of("chunk-2"));
    store.saveEntity(alice);
    store.saveEntity(bob);

    assertThat(store.loadEntities(List.of("entity-2", "missing", "entity-1")))
        .containsExactly(bob, alice);
}
```

- [ ] **Step 2: Run the focused graph store test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryGraphStoreTest"`

Expected: FAIL because `GraphStore` does not yet expose `saveEntities`, `saveRelations`, `loadEntities`, or `loadRelations`.

- [ ] **Step 3: Add default batch methods to GraphStore**

```java
default void saveEntities(List<EntityRecord> entities) {
    for (var entity : List.copyOf(Objects.requireNonNull(entities, "entities"))) {
        saveEntity(entity);
    }
}

default void saveRelations(List<RelationRecord> relations) {
    for (var relation : List.copyOf(Objects.requireNonNull(relations, "relations"))) {
        saveRelation(relation);
    }
}

default List<EntityRecord> loadEntities(List<String> entityIds) {
    var loaded = new java.util.ArrayList<EntityRecord>();
    for (var entityId : List.copyOf(Objects.requireNonNull(entityIds, "entityIds"))) {
        loadEntity(entityId).ifPresent(loaded::add);
    }
    return List.copyOf(loaded);
}

default List<RelationRecord> loadRelations(List<String> relationIds) {
    var loaded = new java.util.ArrayList<RelationRecord>();
    for (var relationId : List.copyOf(Objects.requireNonNull(relationIds, "relationIds"))) {
        loadRelation(relationId).ifPresent(loaded::add);
    }
    return List.copyOf(loaded);
}
```

- [ ] **Step 4: Re-run the focused graph store test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryGraphStoreTest"`

Expected: PASS with batch calls preserving current single-record semantics.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryGraphStoreTest.java
git commit -m "feat: add default graph store batch methods"
```

## Task 2: Batch PostgreSQL Graph Reads And Writes

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java`

- [ ] **Step 1: Add failing PostgreSQL graph store tests for batch save and load**

```java
@Test
void batchSavesAndLoadsEntitiesAndRelations() {
    try (
        var container = newPostgresContainer();
        var resources = newStoreResources(container);
    ) {
        var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
        var bob = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of("B"), List.of("chunk-2"));
        var relation = new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alice knows Bob", 0.9d, List.of("chunk-1"));

        resources.store().saveEntities(List.of(alice, bob));
        resources.store().saveRelations(List.of(relation));

        assertThat(resources.store().loadEntities(List.of("entity-2", "entity-1")))
            .containsExactly(bob, alice);
        assertThat(resources.store().loadRelations(List.of("relation-1")))
            .containsExactly(relation);
    }
}
```

- [ ] **Step 2: Run the PostgreSQL graph store test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest"`

Expected: FAIL because `PostgresGraphStore` still inherits the row-by-row default implementation.

- [ ] **Step 3: Implement JDBC batch writes and `ANY (?)` batch loads in PostgresGraphStore**

```java
@Override
public void saveEntities(List<EntityRecord> entities) {
    var records = List.copyOf(Objects.requireNonNull(entities, "entities"));
    if (records.isEmpty()) {
        return;
    }
    PostgresRetrySupport.execute("save %s entities".formatted(records.size()), () -> {
        connectionAccess.withConnection(connection -> {
            inTransaction(connection, () -> {
                upsertEntities(connection, records);
                replaceEntityAliases(connection, records);
                replaceEntityChunkIds(connection, records);
            });
            return null;
        });
        return null;
    });
}

@Override
public List<EntityRecord> loadEntities(List<String> entityIds) {
    var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
    if (ids.isEmpty()) {
        return List.of();
    }
    return connectionAccess.withConnection(connection -> selectEntities(connection, ids));
}
```

- [ ] **Step 4: Re-run the PostgreSQL graph store test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest"`

Expected: PASS with the new batch tests and the existing single-record tests.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java
git commit -m "feat: batch postgres graph persistence"
```

## Task 3: Batch Neo4j Graph Reads And Writes

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`

- [ ] **Step 1: Add failing Neo4j tests for batch entity and relation persistence**

```java
@Test
void batchSavesAndLoadsEntitiesAndRelationsWithinWorkspace() {
    try (var store = newStore("alpha")) {
        var alice = entity("entity-1", "Alice");
        var bob = entity("entity-2", "Bob");
        var relation = relation("relation-1", "entity-1", "entity-2", "Alice knows Bob");

        store.saveEntities(List.of(alice, bob));
        store.saveRelations(List.of(relation));

        assertThat(store.loadEntities(List.of("entity-2", "entity-1"))).containsExactly(bob, alice);
        assertThat(store.loadRelations(List.of("relation-1"))).containsExactly(relation);
    }
}
```

- [ ] **Step 2: Run the Neo4j graph store test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest"`

Expected: FAIL because `WorkspaceScopedNeo4jGraphStore` does not yet override the new batch methods.

- [ ] **Step 3: Implement `UNWIND`-based batch save/load paths**

```java
@Override
public void saveEntities(List<EntityRecord> entities) {
    var records = List.copyOf(Objects.requireNonNull(entities, "entities"));
    if (records.isEmpty()) {
        return;
    }
    session.executeWrite(tx -> {
        tx.run(
            """
            UNWIND $rows AS row
            MERGE (entity:Entity {scopedId: row.scopedId})
            SET entity.workspaceId = $workspaceId,
                entity.id = row.id,
                entity.name = row.name,
                entity.type = row.type,
                entity.description = row.description,
                entity.aliases = row.aliases,
                entity.sourceChunkIds = row.sourceChunkIds,
                entity.materialized = true
            """,
            parameters("workspaceId", workspaceId, "rows", rows(records))
        );
        return null;
    });
}
```

- [ ] **Step 4: Re-run the Neo4j graph store test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest"`

Expected: PASS with workspace isolation unchanged and batch writes using a single transaction per list.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java
git commit -m "feat: batch neo4j graph persistence"
```

## Task 4: Preserve Batch Semantics Through Storage Wrappers And Atomic Coordination

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProviderTest.java`

- [ ] **Step 1: Add failing provider/coordinator tests for batch graph writes in atomic views, including MySQL composition**

```java
@Test
void topLevelGraphBatchWritesMirrorIntoAtomicGraphView() {
    try (var provider = newProvider(new FileSnapshotStore())) {
        var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
        var relation = new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alice knows Bob", 0.9d, List.of("chunk-1"));

        provider.graphStore().saveEntities(List.of(alice));
        provider.graphStore().saveRelations(List.of(relation));

        provider.writeAtomically(storage -> {
            assertThat(storage.graphStore().loadEntities(List.of("entity-1"))).containsExactly(alice);
            assertThat(storage.graphStore().loadRelations(List.of("relation-1"))).containsExactly(relation);
            return null;
        });
    }
}

@Test
void mysqlProviderAppliesBatchGraphWritesThroughProjectionAdapter() throws Exception {
    try (
        var container = newMySqlContainer();
        var dataSource = newDataSource(startedConfig(container))
    ) {
        var config = startedConfig(container);
        new MySqlSchemaManager(dataSource, config).bootstrap();
        var graphProjection = new RecordingGraphProjection();
        var vectorProjection = new RecordingVectorProjection();

        try (var provider = new MySqlMilvusNeo4jStorageProvider(
            dataSource,
            config,
            new InMemorySnapshotStore(),
            new WorkspaceScope("default"),
            graphProjection,
            vectorProjection
        )) {
            provider.writeAtomically(storage -> {
                storage.graphStore().saveEntities(List.of(
                    new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("doc-1:0")),
                    new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of("B"), List.of("doc-1:0"))
                ));
                storage.graphStore().saveRelations(List.of(
                    new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alice knows Bob", 0.9d, List.of("doc-1:0"))
                ));
                return null;
            });

            assertThat(graphProjection.allEntities()).hasSize(2);
            assertThat(graphProjection.allRelations()).hasSize(1);
        }
    }
}
```

- [ ] **Step 2: Run the provider tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProviderTest"`

Expected: FAIL because wrapper stores, `StagedGraphStore`, and the MySQL projection adapter still route batch calls through inherited row-by-row defaults.

- [ ] **Step 3: Override batch methods in the locking wrappers, staged coordinator store, and MySQL projection adapter**

```java
@Override
public void saveEntities(List<EntityRecord> entities) {
    withWriteLock(() -> delegate.saveEntities(entities));
}

@Override
public List<EntityRecord> loadEntities(List<String> entityIds) {
    return withReadLock(() -> delegate.loadEntities(entityIds));
}
```

```java
@Override
public void saveEntities(List<EntityRecord> entities) {
    var records = List.copyOf(Objects.requireNonNull(entities, "entities"));
    if (transactionalBase != null) {
        transactionalBase.saveEntities(records);
    }
    for (var record : records) {
        stagedEntities.put(record.id(), record);
    }
}
```

```java
@Override
public void apply(StagedGraphWrites writes) {
    if (!writes.entities().isEmpty()) {
        projection.saveEntities(writes.entities());
    }
    if (!writes.relations().isEmpty()) {
        projection.saveRelations(writes.relations());
    }
}
```

- [ ] **Step 4: Re-run the provider tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProviderTest"`

Expected: PASS with batch graph writes visible in top-level stores, inside atomic views, and across the MySQL composition provider.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProviderTest.java
git commit -m "feat: preserve batch graph writes through storage coordination"
```

## Task 5: Switch IndexingPipeline To Batch Graph Merge Persistence

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java`

- [ ] **Step 1: Write a failing indexing test that verifies batch graph APIs are used**

```java
@Test
void saveGraphUsesBatchLoadAndSaveCalls() {
    var storage = new RecordingAtomicStorageProvider();
    var pipeline = new IndexingPipeline(
        new FakeChatModel(),
        new FakeEmbeddingModel(),
        storage,
        null
    );

    pipeline.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

    assertThat(storage.batchEntityLoads()).isPositive();
    assertThat(storage.batchRelationLoads()).isPositive();
    assertThat(storage.batchEntitySaves()).isPositive();
    assertThat(storage.batchRelationSaves()).isPositive();
}
```

- [ ] **Step 2: Run the focused indexing test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest"`

Expected: FAIL because `IndexingPipeline.saveGraph(...)` still loops over `loadEntity/saveEntity` and `loadRelation/saveRelation`.

- [ ] **Step 3: Refactor IndexingPipeline.saveGraph to merge in batches**

```java
private void saveGraph(List<Entity> entities, List<Relation> relations, AtomicStorageProvider.AtomicStorageView storage) {
    var entityRecords = entities.stream().map(IndexingPipeline::toEntityRecord).toList();
    var relationRecords = relations.stream().map(IndexingPipeline::toRelationRecord).toList();

    var existingEntities = storage.graphStore().loadEntities(entityRecords.stream().map(GraphStore.EntityRecord::id).toList())
        .stream()
        .collect(java.util.stream.Collectors.toMap(GraphStore.EntityRecord::id, java.util.function.Function.identity()));
    var existingRelations = storage.graphStore().loadRelations(relationRecords.stream().map(GraphStore.RelationRecord::id).toList())
        .stream()
        .collect(java.util.stream.Collectors.toMap(GraphStore.RelationRecord::id, java.util.function.Function.identity()));

    storage.graphStore().saveEntities(entityRecords.stream()
        .map(record -> existingEntities.containsKey(record.id()) ? mergeEntity(existingEntities.get(record.id()), toEntity(record)) : record)
        .toList());
    storage.graphStore().saveRelations(relationRecords.stream()
        .map(record -> existingRelations.containsKey(record.id()) ? mergeRelation(existingRelations.get(record.id()), toRelation(record)) : record)
        .toList());
}
```

- [ ] **Step 4: Re-run the focused indexing test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest"`

Expected: PASS with the recording storage seeing batch load/save calls instead of only single-record calls.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java
git commit -m "feat: batch graph persistence in indexing pipeline"
```

## Task 6: Switch GraphMaterializationPipeline And Verify End-To-End Behavior

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java`

- [ ] **Step 1: Add a failing materialization test for batch graph merge persistence**

```java
@Test
void repairChunkUsesBatchGraphPersistence() {
    var storage = new RecordingInMemoryStorageProvider();
    seedDocumentGraphState(storage, "doc-1", Instant.parse("2026-04-12T00:00:00Z"), List.of(
        chunkSnapshot("doc-1", "doc-1:0", 0, "Alice works with Bob")
    ));

    var pipeline = new GraphMaterializationPipeline(
        new FakeChatModel(),
        new FakeEmbeddingModel(),
        storage,
        io.github.lightrag.indexing.refinement.ExtractionRefinementOptions.disabled(),
        null,
        TaskMetadataReporter.noop(),
        IndexingProgressListener.noop()
    );

    pipeline.repairChunk("doc-1", "doc-1:0");

    assertThat(storage.batchEntityLoads()).isPositive();
    assertThat(storage.batchRelationSaves()).isPositive();
}
```

- [ ] **Step 2: Run the materialization test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest"`

Expected: FAIL because `GraphMaterializationPipeline.saveGraph(...)` still uses one-record-at-a-time persistence.

- [ ] **Step 3: Refactor GraphMaterializationPipeline.saveGraph to reuse the same batch merge pattern**

```java
private void saveGraph(List<Entity> entities, List<Relation> relations, AtomicStorageProvider.AtomicStorageView storage) {
    var entityRecords = entities.stream().map(GraphMaterializationPipeline::toEntityRecord).toList();
    var relationRecords = relations.stream().map(GraphMaterializationPipeline::toRelationRecord).toList();
    var existingEntities = indexById(storage.graphStore().loadEntities(entityRecords.stream().map(GraphStore.EntityRecord::id).toList()));
    var existingRelations = indexById(storage.graphStore().loadRelations(relationRecords.stream().map(GraphStore.RelationRecord::id).toList()));

    storage.graphStore().saveEntities(mergeEntities(entityRecords, existingEntities));
    storage.graphStore().saveRelations(mergeRelations(relationRecords, existingRelations));
}
```

- [ ] **Step 4: Run the targeted regression suite to verify the full change passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest" --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest" --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest" --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest"`

Expected: PASS with existing graph materialization durability behavior unchanged and new batch persistence assertions green.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java
git commit -m "feat: batch graph persistence in materialization pipeline"
```

## Task 7: Final Verification

**Files:**
- Modify: none
- Test: `lightrag-core` targeted storage/indexing suite

- [ ] **Step 1: Run the focused verification suite**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryGraphStoreTest" --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest" --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest" --tests "io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProviderTest" --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest"`

Expected: PASS

- [ ] **Step 2: Run one end-to-end provider check for rollback safety**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.postgresIngestRollsBackWhenExtractionFailsAfterChunkPersistence" --tests "io.github.lightrag.E2ELightRagTest.postgresProviderSupportsIngestAndQueryModes" --tests "io.github.lightrag.E2ELightRagTest.postgresNeo4jProviderSupportsIngestAndQueryModes"`

Expected: PASS with no regression in transactional rollback or mixed Postgres+Neo4j runtime behavior.

- [ ] **Step 3: Review `git diff` for accidental API drift**

Run: `git diff --stat && git diff -- lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java`

Expected: Only the planned storage/indexing/test files change, and `GraphStore` API growth is limited to the four batch methods.

- [ ] **Step 4: Commit the verification checkpoint**

```bash
git add -A
git commit -m "test: verify batch graph persistence end to end"
```
