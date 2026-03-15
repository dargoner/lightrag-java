# Java LightRAG Production Storage Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PostgreSQL-backed durable storage provider with `pgvector` support while preserving the current SDK API and provider-level atomic ingest semantics.

**Architecture:** Implement a new `storage.postgres` backend package that satisfies the existing `AtomicStorageProvider` SPI. Use PostgreSQL as the single transactional source of truth for documents, chunks, graph records, vectors, and snapshot restore operations. Keep the graph model relational in this phase so one database transaction can still cover a full ingest operation.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, AssertJ, Jackson, PostgreSQL JDBC, HikariCP, pgvector-java, Testcontainers PostgreSQL

---

## Prerequisites

- Docker must be available locally for Testcontainers-backed integration tests.
- A pgvector-enabled PostgreSQL image such as `pgvector/pgvector:pg16` must be pullable by Testcontainers.
- The current in-memory provider and query tests must remain green throughout this phase.

## File Structure

Planned repository layout and file responsibilities:

- Modify: `build.gradle.kts`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageConfig.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/JdbcJsonCodec.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresDocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresChunkStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresGraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresVectorStore.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresDocumentStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresChunkStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresGraphStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresVectorStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `README.md`

Implementation note:

- Reuse the existing `SnapshotStore` SPI by injecting a delegated snapshot store into `PostgresStorageProvider`; do not add a database-backed snapshot format in this phase.

## Chunk 1: Build And SQL Test Harness

### Task 1: Add PostgreSQL backend dependencies and integration test harness

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write the failing dependency-backed smoke test**

Add a minimal test class scaffold in `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java` that imports:

- `org.testcontainers.containers.PostgreSQLContainer`
- `com.pgvector.PGvector`
- `com.zaxxer.hikari.HikariDataSource`

Expected initial failure: compile errors because the dependencies are missing.

- [ ] **Step 2: Run the smoke test to verify the build fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: FAIL with missing PostgreSQL/Testcontainers classes.

- [ ] **Step 3: Add the minimal dependencies**

Update `build.gradle.kts` to add:

- `implementation("org.postgresql:postgresql:42.7.10")`
- `implementation("com.zaxxer:HikariCP:7.0.2")`
- `implementation("com.pgvector:pgvector:0.1.6")`
- `testImplementation("org.testcontainers:junit-jupiter:1.21.4")`
- `testImplementation("org.testcontainers:postgresql:1.21.4")`

- [ ] **Step 4: Re-run the smoke test**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: FAIL because the backend classes do not exist yet, not because dependencies are missing.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "build: add PostgreSQL storage dependencies"
```

### Task 2: Add PostgreSQL config and schema bootstrap

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageConfig.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/JdbcJsonCodec.java`
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Write the failing schema bootstrap test**

Create a test that:

1. starts a PostgreSQL Testcontainer
2. builds a `PostgresStorageConfig`
3. constructs the future provider
4. asserts the schema bootstrap creates:
   - `rag_documents`
   - `rag_chunks`
   - `rag_entities`
   - `rag_entity_aliases`
   - `rag_entity_chunks`
   - `rag_relations`
   - `rag_relation_chunks`
   - `rag_vectors`

- [ ] **Step 2: Run the bootstrap test**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: FAIL because the config and schema classes do not exist.

- [ ] **Step 3: Implement the minimal config and schema manager**

Requirements:

- `PostgresStorageConfig` carries JDBC URL, credentials, schema, vector dimensions, and table prefix
- `PostgresSchemaManager` creates the `vector` extension if missing
- bootstrap SQL is idempotent
- `JdbcJsonCodec` serializes `Map<String, String>` and `List<String>` fields through Jackson
- Testcontainers should use `DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")`

- [ ] **Step 4: Re-run the bootstrap test**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: PASS for schema bootstrap

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "feat: add PostgreSQL schema bootstrap"
```

## Chunk 2: Durable Document, Chunk, And Vector Stores

### Task 3: Implement PostgreSQL document and chunk stores

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresDocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresChunkStore.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresDocumentStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresChunkStoreTest.java`

- [ ] **Step 1: Write the failing document store tests**

Add tests for:

- save and load a document
- list returns deterministic order by ID
- contains reflects stored IDs

- [ ] **Step 2: Write the failing chunk store tests**

Add tests for:

- save and load a chunk
- list returns deterministic order by ID
- `listByDocument` returns chunks ordered by `order`

- [ ] **Step 3: Run the document and chunk tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresDocumentStoreTest --tests io.github.lightragjava.storage.postgres.PostgresChunkStoreTest`
Expected: FAIL because the JDBC stores do not exist.

- [ ] **Step 4: Implement the minimal JDBC stores**

Requirements:

- use plain JDBC prepared statements
- persist metadata as JSON text
- use deterministic ordering in all list operations
- do not embed transaction ownership in the store classes

- [ ] **Step 5: Re-run the document and chunk tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresDocumentStoreTest --tests io.github.lightragjava.storage.postgres.PostgresChunkStoreTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresDocumentStore.java src/main/java/io/github/lightragjava/storage/postgres/PostgresChunkStore.java src/test/java/io/github/lightragjava/storage/postgres/PostgresDocumentStoreTest.java src/test/java/io/github/lightragjava/storage/postgres/PostgresChunkStoreTest.java
git commit -m "feat: add PostgreSQL document and chunk stores"
```

### Task 4: Implement PostgreSQL vector storage with pgvector

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresVectorStore.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresVectorStoreTest.java`

- [ ] **Step 1: Write the failing vector store tests**

Add tests for:

- storing vectors by namespace and ID
- returning vectors in deterministic order from `list`
- top-K similarity search by namespace
- rejecting mismatched vector dimensions

- [ ] **Step 2: Run the vector store tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresVectorStoreTest`
Expected: FAIL because the pgvector-backed store does not exist.

- [ ] **Step 3: Implement the minimal vector store**

Requirements:

- register `PGvector` types on each connection
- store one row per namespace and vector ID
- use namespace-scoped nearest-neighbor queries
- match the current in-memory inner-product ordering contract
- preserve the current `VectorStore` contract

- [ ] **Step 4: Re-run the vector store tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresVectorStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresVectorStore.java src/test/java/io/github/lightragjava/storage/postgres/PostgresVectorStoreTest.java
git commit -m "feat: add PostgreSQL vector storage"
```

## Chunk 3: Relational Graph Store And Atomic Provider

### Task 5: Implement PostgreSQL graph storage

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresGraphStore.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresGraphStoreTest.java`

- [ ] **Step 1: Write the failing graph store tests**

Add tests for:

- saving and loading entities
- saving and loading relations
- listing all entities and relations in deterministic order
- `findRelations(entityId)` returning one-hop relations
- overwriting a relation updates endpoint indexing

- [ ] **Step 2: Run the graph store tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresGraphStoreTest`
Expected: FAIL because the relational graph store does not exist.

- [ ] **Step 3: Implement the minimal graph store**

Requirements:

- store entities, aliases, and source chunk IDs in relational tables
- store relations and relation source chunk IDs in relational tables
- implement one-hop relation lookup without changing the `GraphStore` SPI

- [ ] **Step 4: Re-run the graph store tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresGraphStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresGraphStore.java src/test/java/io/github/lightragjava/storage/postgres/PostgresGraphStoreTest.java
git commit -m "feat: add PostgreSQL graph storage"
```

### Task 6: Implement the PostgreSQL atomic storage provider

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Create: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Write the failing provider tests**

Add integration tests for:

- exposing consistent top-level store instances while using transaction-bound stores inside atomic writes
- `writeAtomically(...)` rolling back document, chunk, graph, and vector writes on failure
- `restore(Snapshot)` replacing current provider state

- [ ] **Step 2: Run the provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest --tests io.github.lightragjava.indexing.DocumentIngestorTest`
Expected: FAIL because the provider does not exist.

- [ ] **Step 3: Implement the provider**

Requirements:

- use `HikariDataSource`
- open one JDBC connection per atomic operation
- disable auto-commit within atomic blocks
- commit on success and rollback on failure
- keep a stable read-path store set for `documentStore()`, `chunkStore()`, `graphStore()`, and `vectorStore()`
- delegate `snapshotStore()` to a configured `SnapshotStore`
- implement `restore(Snapshot)` with table truncation plus bulk reinsert inside one transaction

- [ ] **Step 4: Re-run the provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest --tests io.github.lightragjava.indexing.DocumentIngestorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java
git commit -m "feat: add PostgreSQL storage provider"
```

## Chunk 4: End-To-End Parity And Documentation

### Task 7: Add PostgreSQL-backed end-to-end parity coverage

**Files:**
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing PostgreSQL E2E tests**

Add tests that:

- build `LightRag` with `PostgresStorageProvider`
- ingest documents and query successfully
- preserve `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX` behavior parity at a smoke-test level
- verify a failure after chunk persistence does not leave chunk vectors committed
- restore from a saved snapshot into the PostgreSQL-backed provider

- [ ] **Step 2: Run the E2E test class**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`
Expected: FAIL because the durable provider is not fully wired.

- [ ] **Step 3: Implement the minimum integration fixes**

Requirements:

- no public API changes
- refactor `IndexingPipeline` so document/chunk writes, chunk vectors, graph writes, and graph vectors execute inside one `writeAtomically(...)` call
- current query engine and indexing pipeline must work with the PostgreSQL provider
- snapshot autosave should continue using the delegated `SnapshotStore`
- snapshot restore must succeed before `build()`

- [ ] **Step 4: Re-run the E2E test class**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: make ingest atomic for PostgreSQL parity"
```

### Task 8: Document the durable storage backend

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the failing documentation checklist**

Update the README to include:

- PostgreSQL provider purpose
- required extensions and local prerequisites
- quick-start example using `PostgresStorageProvider`
- note that snapshots remain delegated to the configured `SnapshotStore`
- note that `Neo4j` support is planned for the next storage phase

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: Finalize the README updates**

Keep the README focused on:

- when to use in-memory storage
- when to use PostgreSQL storage
- current operational limitations

- [ ] **Step 4: Run the full test suite again**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: add PostgreSQL storage usage"
```

## Verification Commands

Run these before claiming this phase is complete:

- `./gradlew test`
- `git status --short`
- `git log --oneline --decorate -5`

## Follow-On Design

This plan intentionally stops short of a Neo4j-backed graph store. Once the PostgreSQL durable provider is stable, the next design should cover:

- `Neo4jGraphStore`
- mixed backend consistency strategy
- graph projection or synchronization behavior
- native graph traversal optimization for query strategies
