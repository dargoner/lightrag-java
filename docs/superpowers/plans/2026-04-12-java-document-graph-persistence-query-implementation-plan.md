# Document Graph Persistence And Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement durable PostgreSQL/MySQL document-graph snapshot and journal persistence so inspect, chunk-status query, repair, and resume survive restart without changing the public Java SDK APIs.

**Architecture:** Add workspace-scoped SQL-backed `DocumentGraphSnapshotStore` and `DocumentGraphJournalStore` implementations for PostgreSQL and MySQL, bootstrap the four graph-state tables in each schema manager, and replace the current in-memory wiring in relational providers/adapters. Keep `GraphMaterializationPipeline` as the reconciliation layer, but make full snapshot rewrites clear stale chunk journal rows so the durable state stays verifiable and repairable.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Testcontainers, PostgreSQL (`pgvector/pgvector:pg16`), MySQL 8.4, existing LightRag storage SPI and task/query APIs.

---

## File Structure

**Create**

- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphSnapshotStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphJournalStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphSnapshotStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphJournalStore.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphStoresTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphStoresTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/api/LightRagRelationalDocumentGraphPersistenceTest.java`

**Modify**

- `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphSnapshotStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphJournalStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/JdbcJsonCodec.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlJsonCodec.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresRelationalStorageAdapter.java`
- `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java`
- `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryDocumentGraphStoresTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

**Keep As Regression Coverage**

- `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentGraphApiTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java`

### Task 1: Align Current-State Semantics In Shared Stores

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphSnapshotStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphJournalStore.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryDocumentGraphStoresTest.java`

- [ ] **Step 1: Write the failing tests for replacement semantics**

```java
@Test
void snapshotStoreReplacesChunkSetForDocument() {
    var snapshots = InMemoryStorageProvider.create().documentGraphSnapshotStore();
    var now = Instant.now();

    snapshots.saveChunks("doc-1", List.of(
        chunkSnapshot("doc-1", "chunk-1", now),
        chunkSnapshot("doc-1", "chunk-2", now.plusSeconds(1))
    ));
    snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "chunk-2", now.plusSeconds(2))));

    assertThat(snapshots.listChunks("doc-1"))
        .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
        .containsExactly("chunk-2");
}

@Test
void journalStoreKeepsLatestRowsPerDocumentAndChunk() {
    var journals = InMemoryStorageProvider.create().documentGraphJournalStore();
    var now = Instant.now();

    journals.appendDocument(documentJournal("doc-1", 1, now.minusSeconds(2)));
    journals.appendDocument(documentJournal("doc-1", 2, now.minusSeconds(1)));
    journals.appendChunks("doc-1", List.of(chunkJournal("doc-1", "chunk-1", 1, now.minusSeconds(2))));
    journals.appendChunks("doc-1", List.of(chunkJournal("doc-1", "chunk-1", 2, now.minusSeconds(1))));

    assertThat(journals.listDocumentJournals("doc-1"))
        .extracting(DocumentGraphJournalStore.DocumentGraphJournal::snapshotVersion)
        .containsExactly(2);
    assertThat(journals.listChunkJournals("doc-1"))
        .extracting(DocumentGraphJournalStore.ChunkGraphJournal::snapshotVersion)
        .containsExactly(2);
}
```

- [ ] **Step 2: Run the focused store test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryDocumentGraphStoresTest"`

Expected: FAIL because chunk replacement and latest-row journal semantics are still append-only.

- [ ] **Step 3: Implement the minimal in-memory semantic changes**

```java
@Override
public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
    var normalizedDocumentId = normalizeDocumentId(documentId);
    var copy = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    for (var chunk : copy) {
        Objects.requireNonNull(chunk, "chunk");
        if (!chunk.documentId().equals(normalizedDocumentId)) {
            throw new IllegalArgumentException("chunk documentId must match documentId");
        }
    }
    chunkSnapshots.put(normalizedDocumentId, copy);
}

@Override
public void appendDocument(DocumentGraphJournal journal) {
    var entry = Objects.requireNonNull(journal, "journal");
    documentJournals.put(entry.documentId(), List.of(entry));
}

@Override
public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
    var normalizedDocumentId = normalizeDocumentId(documentId);
    var merged = new LinkedHashMap<String, ChunkGraphJournal>();
    listChunkJournals(normalizedDocumentId).forEach(journal -> merged.put(journal.chunkId(), journal));
    for (var journal : List.copyOf(journals)) {
        if (!journal.documentId().equals(normalizedDocumentId)) {
            throw new IllegalArgumentException("journal documentId must match documentId");
        }
        merged.put(journal.chunkId(), journal);
    }
    chunkJournals.put(normalizedDocumentId, List.copyOf(merged.values()));
}
```

- [ ] **Step 4: Run the store test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryDocumentGraphStoresTest"`

Expected: PASS with the new replacement/upsert semantics locked in.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphSnapshotStore.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphJournalStore.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryDocumentGraphStoresTest.java
git commit -m "refactor: align in-memory document graph state semantics"
```

### Task 2: Add PostgreSQL Graph-State Tables And Stores

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphSnapshotStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphJournalStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/JdbcJsonCodec.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphStoresTest.java`

- [ ] **Step 1: Write the failing PostgreSQL store tests**

```java
@Test
void snapshotAndJournalStoresRoundTripDurableGraphState() {
    try (var container = newPostgresContainer(); var resources = newStoreResources(container, "default")) {
        var now = Instant.parse("2026-04-12T10:00:00Z");
        resources.snapshotStore().saveDocument(graphSnapshot("doc-1", now));
        resources.snapshotStore().saveChunks("doc-1", List.of(chunkGraphSnapshot("doc-1", "doc-1:0", now)));
        resources.journalStore().appendDocument(documentGraphJournal("doc-1", 1, now.plusSeconds(1)));
        resources.journalStore().appendChunks("doc-1", List.of(chunkGraphJournal("doc-1", "doc-1:0", 1, now.plusSeconds(2))));

        assertThat(resources.snapshotStore().loadDocument("doc-1")).isPresent();
        assertThat(resources.snapshotStore().listChunks("doc-1")).hasSize(1);
        assertThat(resources.journalStore().listDocumentJournals("doc-1")).hasSize(1);
        assertThat(resources.journalStore().listChunkJournals("doc-1")).hasSize(1);
    }
}

@Test
void saveChunksRemovesRowsThatAreNoLongerInTheCurrentSnapshot() {
    try (var container = newPostgresContainer(); var resources = newStoreResources(container, "default")) {
        var now = Instant.parse("2026-04-12T10:00:00Z");
        resources.snapshotStore().saveChunks("doc-1", List.of(
            chunkGraphSnapshot("doc-1", "doc-1:0", now),
            chunkGraphSnapshot("doc-1", "doc-1:1", now.plusSeconds(1))
        ));
        resources.snapshotStore().saveChunks("doc-1", List.of(chunkGraphSnapshot("doc-1", "doc-1:1", now.plusSeconds(2))));

        assertThat(resources.snapshotStore().listChunks("doc-1"))
            .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
            .containsExactly("doc-1:1");
    }
}
```

- [ ] **Step 2: Run the PostgreSQL store tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresDocumentGraphStoresTest"`

Expected: FAIL because the PostgreSQL graph-state store classes and schema tables do not exist yet.

- [ ] **Step 3: Implement PostgreSQL schema and SQL-backed stores**

```java
// PostgresSchemaManager.java
private List<Migration> migrations() {
    return List.of(
        new Migration(3, versionThreeStatements()),
        new Migration(4, versionFourStatements()),
        new Migration(5, versionFiveStatements())
    );
}

private List<String> versionFiveStatements() {
    return List.of(
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id TEXT NOT NULL,
            document_id TEXT NOT NULL,
            version INTEGER NOT NULL,
            status TEXT NOT NULL,
            source TEXT NOT NULL,
            chunk_count INTEGER NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id)
        )
        """.formatted(config.qualifiedTableName("document_graph_snapshots")),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id TEXT NOT NULL,
            document_id TEXT NOT NULL,
            chunk_id TEXT NOT NULL,
            chunk_order INTEGER NOT NULL,
            content_hash TEXT NOT NULL,
            extract_status TEXT NOT NULL,
            entities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            relations_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            updated_at TIMESTAMPTZ NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id, chunk_id)
        )
        """.formatted(config.qualifiedTableName("chunk_graph_snapshots")),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id TEXT NOT NULL,
            document_id TEXT NOT NULL,
            snapshot_version INTEGER NOT NULL,
            status TEXT NOT NULL,
            last_mode TEXT NOT NULL,
            expected_entity_count INTEGER NOT NULL,
            expected_relation_count INTEGER NOT NULL,
            materialized_entity_count INTEGER NOT NULL,
            materialized_relation_count INTEGER NOT NULL,
            last_failure_stage TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id)
        )
        """.formatted(config.qualifiedTableName("document_graph_journals")),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id TEXT NOT NULL,
            document_id TEXT NOT NULL,
            chunk_id TEXT NOT NULL,
            snapshot_version INTEGER NOT NULL,
            merge_status TEXT NOT NULL,
            graph_status TEXT NOT NULL,
            expected_entity_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            expected_relation_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            materialized_entity_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            materialized_relation_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
            last_failure_stage TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id, chunk_id)
        )
        """.formatted(config.qualifiedTableName("chunk_graph_journals"))
    );
}

// PostgresDocumentGraphSnapshotStore.java
public final class PostgresDocumentGraphSnapshotStore implements DocumentGraphSnapshotStore {
    @Override
    public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
        connectionAccess.withConnection(connection -> {
            deleteMissingChunkRows(connection, documentId, chunks.stream().map(ChunkGraphSnapshot::chunkId).toList());
            upsertChunkRows(connection, chunks);
            return null;
        });
    }
}

// PostgresDocumentGraphJournalStore.java
public final class PostgresDocumentGraphJournalStore implements DocumentGraphJournalStore {
    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        upsertDocumentJournal(journal);
    }

    @Override
    public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
        upsertChunkJournalRows(documentId, journals);
    }
}
```

- [ ] **Step 4: Run the PostgreSQL store tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresDocumentGraphStoresTest"`

Expected: PASS with durable PostgreSQL graph-state rows and workspace isolation.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphSnapshotStore.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphJournalStore.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/JdbcJsonCodec.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresDocumentGraphStoresTest.java
git commit -m "feat: persist document graph state in postgres"
```

### Task 3: Add MySQL Graph-State Tables And Stores

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphSnapshotStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphJournalStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlJsonCodec.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphStoresTest.java`

- [ ] **Step 1: Write the failing MySQL store tests**

```java
@Test
void snapshotAndJournalStoresRoundTripDurableGraphState() {
    try (var container = newMySqlContainer(); var resources = newStoreResources(container, "default")) {
        var now = Instant.parse("2026-04-12T10:00:00Z");
        resources.snapshotStore().saveDocument(graphSnapshot("doc-1", now));
        resources.snapshotStore().saveChunks("doc-1", List.of(chunkGraphSnapshot("doc-1", "doc-1:0", now)));
        resources.journalStore().appendDocument(documentGraphJournal("doc-1", 1, now.plusSeconds(1)));
        resources.journalStore().appendChunks("doc-1", List.of(chunkGraphJournal("doc-1", "doc-1:0", 1, now.plusSeconds(2))));

        assertThat(resources.snapshotStore().loadDocument("doc-1")).isPresent();
        assertThat(resources.journalStore().listChunkJournals("doc-1")).singleElement()
            .extracting(DocumentGraphJournalStore.ChunkGraphJournal::chunkId)
            .isEqualTo("doc-1:0");
    }
}

@Test
void appendChunksUpsertsLatestChunkRowsPerChunkId() {
    try (var container = newMySqlContainer(); var resources = newStoreResources(container, "default")) {
        var now = Instant.parse("2026-04-12T10:00:00Z");
        resources.journalStore().appendChunks("doc-1", List.of(chunkGraphJournal("doc-1", "doc-1:0", 1, now)));
        resources.journalStore().appendChunks("doc-1", List.of(chunkGraphJournal("doc-1", "doc-1:0", 2, now.plusSeconds(1))));

        assertThat(resources.journalStore().listChunkJournals("doc-1"))
            .extracting(DocumentGraphJournalStore.ChunkGraphJournal::snapshotVersion)
            .containsExactly(2);
    }
}
```

- [ ] **Step 2: Run the MySQL store tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.mysql.MySqlDocumentGraphStoresTest"`

Expected: FAIL because the MySQL graph-state store classes and schema tables do not exist yet.

- [ ] **Step 3: Implement MySQL schema and SQL-backed stores**

```java
// MySqlSchemaManager.java
private List<Migration> migrations() {
    return List.of(
        new Migration(1, versionOneStatements()),
        new Migration(2, versionTwoStatements()),
        new Migration(3, versionThreeStatements())
    );
}

private List<String> versionThreeStatements() {
    return List.of(
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id VARCHAR(191) NOT NULL,
            document_id VARCHAR(191) NOT NULL,
            version INT NOT NULL,
            status VARCHAR(64) NOT NULL,
            source VARCHAR(64) NOT NULL,
            chunk_count INT NOT NULL,
            created_at DATETIME(6) NOT NULL,
            updated_at DATETIME(6) NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id)
        )
        """.formatted(config.qualifiedTableName("document_graph_snapshots")),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id VARCHAR(191) NOT NULL,
            document_id VARCHAR(191) NOT NULL,
            chunk_id VARCHAR(191) NOT NULL,
            chunk_order INT NOT NULL,
            content_hash VARCHAR(191) NOT NULL,
            extract_status VARCHAR(64) NOT NULL,
            entities_json JSON NOT NULL,
            relations_json JSON NOT NULL,
            updated_at DATETIME(6) NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id, chunk_id),
            KEY %s (workspace_id, document_id, chunk_order, chunk_id)
        )
        """.formatted(
            config.qualifiedTableName("chunk_graph_snapshots"),
            config.tableName("chunk_graph_snapshots") + "_document_order_idx"
        ),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id VARCHAR(191) NOT NULL,
            document_id VARCHAR(191) NOT NULL,
            snapshot_version INT NOT NULL,
            status VARCHAR(64) NOT NULL,
            last_mode VARCHAR(64) NOT NULL,
            expected_entity_count INT NOT NULL,
            expected_relation_count INT NOT NULL,
            materialized_entity_count INT NOT NULL,
            materialized_relation_count INT NOT NULL,
            last_failure_stage VARCHAR(64) NOT NULL,
            created_at DATETIME(6) NOT NULL,
            updated_at DATETIME(6) NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id)
        )
        """.formatted(config.qualifiedTableName("document_graph_journals")),
        """
        CREATE TABLE IF NOT EXISTS %s (
            workspace_id VARCHAR(191) NOT NULL,
            document_id VARCHAR(191) NOT NULL,
            chunk_id VARCHAR(191) NOT NULL,
            snapshot_version INT NOT NULL,
            merge_status VARCHAR(64) NOT NULL,
            graph_status VARCHAR(64) NOT NULL,
            expected_entity_keys_json JSON NOT NULL,
            expected_relation_keys_json JSON NOT NULL,
            materialized_entity_keys_json JSON NOT NULL,
            materialized_relation_keys_json JSON NOT NULL,
            last_failure_stage VARCHAR(64) NOT NULL,
            updated_at DATETIME(6) NOT NULL,
            error_message TEXT NULL,
            PRIMARY KEY (workspace_id, document_id, chunk_id),
            KEY %s (workspace_id, document_id, graph_status, chunk_id)
        )
        """.formatted(
            config.qualifiedTableName("chunk_graph_journals"),
            config.tableName("chunk_graph_journals") + "_graph_status_idx"
        )
    );
}

// MySqlDocumentGraphSnapshotStore.java
public final class MySqlDocumentGraphSnapshotStore implements DocumentGraphSnapshotStore {
    @Override
    public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
        connectionAccess.withConnection(connection -> {
            deleteMissingChunkRows(connection, documentId, chunks.stream().map(ChunkGraphSnapshot::chunkId).toList());
            upsertChunkRows(connection, chunks);
            return null;
        });
    }
}

// MySqlDocumentGraphJournalStore.java
public final class MySqlDocumentGraphJournalStore implements DocumentGraphJournalStore {
    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        upsertDocumentJournal(journal);
    }
}
```

- [ ] **Step 4: Run the MySQL store tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.mysql.MySqlDocumentGraphStoresTest"`

Expected: PASS with MySQL graph-state persistence and current-state semantics.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphSnapshotStore.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphJournalStore.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlJsonCodec.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphStoresTest.java
git commit -m "feat: persist document graph state in mysql"
```

### Task 4: Wire Providers And Full-Snapshot Rewrite Paths

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresRelationalStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

- [ ] **Step 1: Add failing provider and pipeline regression tests**

```java
@Test
void restoreReplacesSqlBackedDocumentGraphStores() {
    try (var provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
        provider.documentGraphSnapshotStore().saveDocument(graphSnapshot("doc-old", now.minusSeconds(10)));
        provider.documentGraphJournalStore().appendChunks("doc-old", List.of(chunkGraphJournal("doc-old", "doc-old:0", 1, now.minusSeconds(9))));

        provider.restore(new SnapshotStore.Snapshot(
            List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(),
            List.of(graphSnapshot("doc-1", now)),
            List.of(chunkGraphSnapshot("doc-1", "doc-1:0", now.plusSeconds(1))),
            List.of(documentGraphJournal("doc-1", 2, now.plusSeconds(2))),
            List.of(chunkGraphJournal("doc-1", "doc-1:0", 2, now.plusSeconds(3)))
        ));

        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-old")).isEmpty();
        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-1")).isPresent();
    }
}

@Test
void fullSnapshotRewriteClearsRemovedChunkJournals() {
    var storage = InMemoryStorageProvider.create();
    seedDocumentGraphState(storage, "doc-1", now, List.of(
        chunkSnapshot("doc-1", "doc-1:0", 0, "Alice works with Bob"),
        chunkSnapshot("doc-1", "doc-1:1", 1, "Bob works with Carol")
    ));
    storage.documentGraphJournalStore().appendChunks("doc-1", List.of(
        chunkJournal("doc-1", "doc-1:0", 1, ChunkGraphStatus.MATERIALIZED, List.of("entity:alice"), List.of()),
        chunkJournal("doc-1", "doc-1:1", 1, ChunkGraphStatus.MATERIALIZED, List.of("entity:bob"), List.of())
    ));

    storage.documentGraphSnapshotStore().saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "doc-1:1", 0, "Bob works with Carol")));
    storage.documentGraphJournalStore().delete("doc-1");
    storage.documentGraphJournalStore().appendChunks("doc-1", List.of(
        chunkJournal("doc-1", "doc-1:1", 2, ChunkGraphStatus.MATERIALIZED, List.of("entity:bob"), List.of())
    ));

    assertThat(storage.documentGraphJournalStore().listChunkJournals("doc-1"))
        .extracting(DocumentGraphJournalStore.ChunkGraphJournal::chunkId)
        .containsExactly("doc-1:1");
}
```

- [ ] **Step 2: Run the provider regression tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest"`

Expected: FAIL because providers/adapters still wire the graph-state SPI to in-memory stores and full snapshot rewrite paths leave stale chunk journals behind.

- [ ] **Step 3: Wire SQL stores and clear stale journals on full rewrites**

```java
// PostgresStorageProvider.java / PostgresRelationalStorageAdapter.java
this.documentGraphSnapshotStore = DocumentGraphStateSupport.trackedSnapshotStore(
    new PostgresDocumentGraphSnapshotStore(jdbcDataSource, resolvedConfig, this.workspaceId),
    trackedDocumentGraphIds
);
this.documentGraphJournalStore = DocumentGraphStateSupport.trackedJournalStore(
    new PostgresDocumentGraphJournalStore(jdbcDataSource, resolvedConfig, this.workspaceId),
    trackedDocumentGraphIds
);

// MySqlRelationalStorageAdapter.java
this.documentGraphSnapshotStore = DocumentGraphStateSupport.trackedSnapshotStore(
    new MySqlDocumentGraphSnapshotStore(dataSource, config, this.workspaceId),
    trackedDocumentGraphIds
);
this.documentGraphJournalStore = DocumentGraphStateSupport.trackedJournalStore(
    new MySqlDocumentGraphJournalStore(dataSource, config, this.workspaceId),
    trackedDocumentGraphIds
);

// IndexingPipeline.java
var documentJournal = new DocumentGraphJournalStore.DocumentGraphJournal(
    documentId,
    snapshotVersion,
    GraphMaterializationStatus.MERGED,
    GraphMaterializationMode.AUTO,
    computed.graph().entities().size(),
    computed.graph().relations().size(),
    computed.graph().entities().size(),
    computed.graph().relations().size(),
    FailureStage.FINALIZING,
    now,
    now,
    null
);
storageProvider.documentGraphJournalStore().delete(documentId);
storageProvider.documentGraphJournalStore().appendDocument(documentJournal);
storageProvider.documentGraphJournalStore().appendChunks(
    documentId,
    toChunkGraphJournals(documentId, snapshotVersion, computed.extractions(), now)
);

// GraphMaterializationPipeline.java
storageProvider.writeAtomically(storage -> {
    storage.documentGraphSnapshotStore().saveDocument(documentSnapshot);
    storage.documentGraphSnapshotStore().saveChunks(documentId, chunkSnapshots);
    storage.documentGraphJournalStore().delete(documentId);
    return null;
});
```

- [ ] **Step 4: Run the provider regression tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest"`

Expected: PASS with SQL-backed provider wiring and no stale chunk journals after a full snapshot rewrite.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresRelationalStorageAdapter.java \
  lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java \
  lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java
git commit -m "feat: wire durable document graph state into relational providers"
```

### Task 5: Add Restart-Safe SDK Query Regression Coverage

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagRelationalDocumentGraphPersistenceTest.java`
- Keep regression: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentGraphApiTest.java`
- Keep regression: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java`

- [ ] **Step 1: Write the failing restart/query integration tests**

```java
@Test
void postgresInspectAndChunkStatusSurviveNewLightRagInstance() {
    try (var container = newPostgresContainer()) {
        var providerA = new PostgresStorageProvider(startedPostgresConfig(container), new InMemorySnapshotStore());
        try (var ragA = newLightRag(providerA)) {
            ragA.ingest("default", List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        }

        var providerB = new PostgresStorageProvider(startedPostgresConfig(container), new InMemorySnapshotStore());
        try (var ragB = newLightRag(providerB)) {
            assertThat(ragB.inspectDocumentGraph("default", "doc-1").snapshotVersion()).isEqualTo(1);
            assertThat(ragB.listDocumentChunkGraphStatuses("default", "doc-1"))
                .extracting(DocumentChunkGraphStatus::chunkId)
                .containsExactly("doc-1:0");
        }
    }
}

@Test
void mySqlRepairUsesPersistedChunkStateAfterProviderRebuild() {
    try (var container = newMySqlContainer();
         var graphProjection = new PersistentGraphProjection();
         var vectorProjection = new PersistentVectorProjection()) {
        var providerA = newProvider(container, graphProjection, vectorProjection);
        try (var ragA = newLightRag(providerA)) {
            ragA.ingest("default", List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        }

        degradePersistedGraph(graphProjection, vectorProjection, "doc-1:0");

        var providerB = newProvider(container, graphProjection, vectorProjection);
        try (var ragB = newLightRag(providerB)) {
            assertThat(ragB.getDocumentChunkGraphStatus("default", "doc-1", "doc-1:0").recommendedAction())
                .isEqualTo(GraphChunkAction.REPAIR);
            assertThat(ragB.repairChunkGraph("default", "doc-1", "doc-1:0").finalStatus())
                .isEqualTo(ChunkGraphStatus.MATERIALIZED);
        }
    }
}
```

- [ ] **Step 2: Run the relational SDK regression tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagRelationalDocumentGraphPersistenceTest"`

Expected: FAIL until the new provider instances can read durable graph-state rows from PostgreSQL/MySQL.

- [ ] **Step 3: Fix any missed query-path gaps exposed by restart tests**

```java
private MaterializationState loadState(String documentId) {
    var chunkSnapshots = storageProvider.documentGraphSnapshotStore().listChunks(normalizedDocumentId);
    var chunkIds = chunkSnapshots.stream()
        .map(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    var latestChunkJournals = latestByKey(
        storageProvider.documentGraphJournalStore().listChunkJournals(normalizedDocumentId).stream()
            .filter(journal -> chunkIds.contains(journal.chunkId()))
            .toList(),
        DocumentGraphJournalStore.ChunkGraphJournal::chunkId,
        DocumentGraphJournalStore.ChunkGraphJournal::updatedAt
    );
    return new MaterializationState(
        normalizedDocumentId,
        documentSnapshot,
        chunkSnapshots,
        latestDocumentJournal,
        latestChunkJournals,
        storageProvider.documentStatusStore().load(normalizedDocumentId)
            .orElse(new DocumentStatusStore.StatusRecord(
                normalizedDocumentId,
                DocumentStatus.FAILED,
                "",
                "document status does not exist"
            )),
        expectedGraph,
        actualEntities,
        actualRelations,
        storedChunks
    );
}
```

- [ ] **Step 4: Run the full regression set to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.InMemoryDocumentGraphStoresTest" --tests "io.github.lightrag.storage.postgres.PostgresDocumentGraphStoresTest" --tests "io.github.lightrag.storage.mysql.MySqlDocumentGraphStoresTest" --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest" --tests "io.github.lightrag.api.LightRagRelationalDocumentGraphPersistenceTest" --tests "io.github.lightrag.api.LightRagTaskApiTest" --tests "io.github.lightrag.api.LightRagDocumentGraphApiTest"`

Expected: PASS with durable graph-state persistence, restart-safe inspect/query behavior, and unchanged public API/task metadata.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/test/java/io/github/lightrag/api/LightRagRelationalDocumentGraphPersistenceTest.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java
git commit -m "test: cover relational document graph persistence across restart"
```

## Self-Review

### Spec Coverage

- Durable PostgreSQL graph snapshot/journal persistence: covered by Task 2 and Task 4.
- Durable MySQL graph snapshot/journal persistence: covered by Task 3 and Task 4.
- Public SDK graph/query APIs unchanged: protected by Task 5 regression plus existing `LightRagDocumentGraphApiTest`.
- Restart-safe inspect/chunk status query: covered by Task 5.
- Snapshot capture/restore compatibility: covered by Task 4 provider tests.
- Workspace scoping and current-state semantics: covered by Task 1, Task 2, and Task 3.
- Full snapshot rewrite removing stale chunk rows/journals: covered by Task 4.
- Task metadata expectations: no planned production change; existing `LightRagTaskApiTest` remains required regression coverage in Task 5 verification.

### Placeholder Scan

- No unfinished markers remain in tasks.
- Every task has exact file paths, commands, and at least one concrete code/test snippet.

### Type Consistency

- Store class names are consistent across tasks:
  `PostgresDocumentGraphSnapshotStore`, `PostgresDocumentGraphJournalStore`,
  `MySqlDocumentGraphSnapshotStore`, `MySqlDocumentGraphJournalStore`.
- Public SDK methods referenced in tests match current API names:
  `inspectDocumentGraph`, `getDocumentChunkGraphStatus`, `listDocumentChunkGraphStatuses`,
  `repairChunkGraph`, `resumeChunkGraph`, `materializeDocumentGraph`.
