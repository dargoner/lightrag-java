# Java LightRAG Neo4j Graph Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Neo4j graph support to the Java LightRAG SDK while preserving the current `AtomicStorageProvider` contract by using PostgreSQL as the durable source of truth and Neo4j as the synchronous graph projection.

**Architecture:** Implement a `storage.neo4j` package for the native Neo4j graph store, then add a mixed provider that composes the existing PostgreSQL provider with Neo4j graph projection and compensation-based rollback. Keep the public builder and query APIs unchanged.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5, AssertJ, PostgreSQL JDBC, HikariCP, pgvector-java, Neo4j Java Driver, Testcontainers PostgreSQL, Testcontainers Neo4j

---

## Prerequisites

- Docker must be available locally for PostgreSQL and Neo4j Testcontainers.
- A `pgvector/pgvector:pg16` image must be pullable for PostgreSQL-backed tests.
- A `neo4j:5-community` image must be pullable for Neo4j-backed tests.
- The current `./gradlew test` baseline on `main` must stay green throughout the phase.

## File Structure

Planned repository layout and file responsibilities:

- Modify: `build.gradle.kts`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphConfig.java`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphSnapshot.java`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`
- Create: `src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `README.md`

## Chunk 1: Neo4j Driver And Native Graph Store

### Task 1: Add Neo4j dependencies and integration test harness

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java`

- [ ] **Step 1: Write the failing smoke test**

Create a minimal `Neo4jGraphStoreTest` that imports:

- `org.neo4j.driver.Driver`
- `org.testcontainers.containers.Neo4jContainer`

Expected initial failure: compile errors because the Neo4j dependencies are missing.

- [ ] **Step 2: Run the smoke test to verify the build fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.Neo4jGraphStoreTest`
Expected: FAIL with missing Neo4j/Testcontainers classes.

- [ ] **Step 3: Add the minimal dependencies**

Update `build.gradle.kts` to add:

- `implementation("org.neo4j.driver:neo4j-java-driver:5.28.5")`
- `testImplementation("org.testcontainers:neo4j:1.21.4")`

- [ ] **Step 4: Re-run the smoke test**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.Neo4jGraphStoreTest`
Expected: FAIL because the backend classes do not exist yet, not because dependencies are missing.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java
git commit -m "build: add Neo4j graph dependencies"
```

### Task 2: Implement Neo4j graph config and native graph store

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphConfig.java`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphSnapshot.java`
- Modify: `src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java`

- [ ] **Step 1: Write the failing store behavior tests**

Add tests for:

- save and load an entity
- save and load a relation
- `allEntities()` returns deterministic order by ID
- `allRelations()` returns deterministic order by ID
- `findRelations(entityId)` returns all one-hop relations ordered by relation ID
- graph snapshot capture and restore

- [ ] **Step 2: Run the Neo4j graph tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.Neo4jGraphStoreTest`
Expected: FAIL because the Neo4j classes do not exist.

- [ ] **Step 3: Implement the minimal Neo4j graph backend**

Requirements:

- validate Bolt URI, credentials, and database name through `Neo4jGraphConfig`
- create entity and relation ID constraints on bootstrap
- map `GraphStore.EntityRecord` to `:Entity`
- map `GraphStore.RelationRecord` to `[:RELATION]`
- store aliases and source chunk IDs as string arrays
- preserve deterministic ordering in all read operations
- add snapshot capture and full-graph restore helpers for provider rollback support

- [ ] **Step 4: Re-run the Neo4j graph tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.Neo4jGraphStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/neo4j src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java
git commit -m "feat: add Neo4j graph store"
```

## Chunk 2: Mixed Provider And Rollback Semantics

### Task 3: Implement the PostgreSQL + Neo4j provider

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`
- Create: `src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java`

- [ ] **Step 1: Write the failing mixed-provider tests**

Add tests for:

- top-level document, chunk, and vector stores delegate to PostgreSQL
- top-level graph reads come from Neo4j while top-level graph writes mirror into PostgreSQL and Neo4j
- `writeAtomically(...)` exposes one atomic view for PostgreSQL stores and graph writes
- `restore(snapshot)` restores PostgreSQL and rebuilds Neo4j graph state

- [ ] **Step 2: Run the provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest`
Expected: FAIL because the mixed provider does not exist.

- [ ] **Step 3: Implement the minimal mixed provider**

Requirements:

- internally compose `PostgresStorageProvider` and `Neo4jGraphStore`
- use PostgreSQL for top-level document/chunk/vector access
- expose a stable top-level graph facade that reads from Neo4j and mirrors writes into PostgreSQL and Neo4j
- capture pre-write PostgreSQL snapshot for `chunks`, `entities`, and `relations` vector namespaces plus graph rows
- capture pre-write Neo4j graph snapshot
- on successful PostgreSQL write, synchronize the graph projection into Neo4j
- on Neo4j projection failure, restore both stores and preserve the original failure

- [ ] **Step 4: Re-run the provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java
git commit -m "feat: add PostgreSQL Neo4j storage provider"
```

### Task 4: Verify end-to-end Neo4j parity and rollback behavior

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java`

- [ ] **Step 1: Write the failing end-to-end coverage**

Add tests for:

- ingest + query using `PostgresNeo4jStorageProvider`
- rollback when Neo4j graph projection fails after PostgreSQL data was persisted
- graph reads after `restore(snapshot)` use rebuilt Neo4j state

- [ ] **Step 2: Run the E2E and provider rollback tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: FAIL because rollback/rebuild behavior is not fully implemented yet.

- [ ] **Step 3: Implement the missing rollback and rebuild behavior**

Requirements:

- make projection sync explicit so tests can inject a failing Neo4j path
- rebuild Neo4j from PostgreSQL graph rows during restore and recovery
- keep failure suppression consistent with the PostgreSQL provider rollback path

- [ ] **Step 4: Re-run the targeted tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover Neo4j projection rollback"
```

## Chunk 3: Documentation And Final Verification

### Task 5: Document Neo4j usage and verify the full suite

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README**

Document:

- the purpose of `PostgresNeo4jStorageProvider`
- required PostgreSQL and Neo4j prerequisites
- a quick-start example using both configs
- the compensation-based rollback model at a high level
- that PostgreSQL remains the source of truth while Neo4j is the graph projection

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add Neo4j storage usage"
```
