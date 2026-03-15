# PostgreSQL Schema Migrations Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add transactional schema version tracking and SDK-managed migrations to the PostgreSQL provider while preserving current bootstrap behavior.

**Architecture:** `PostgresSchemaManager` will own an ordered list of schema migrations and reconcile the configured schema to the latest supported version during provider startup. The first migration reuses the existing bootstrap SQL, while new metadata helpers baseline legacy unversioned schemas and reject unsupported newer versions.

**Tech Stack:** Java 17, JDBC, HikariCP, PostgreSQL, Testcontainers, JUnit 5, AssertJ

---

## Chunk 1: Migration Metadata And Failure Cases

### Task 1: Add failing PostgreSQL schema-manager tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Write the failing test for fresh bootstrap metadata**

Add a Testcontainers integration test that constructs `PostgresStorageProvider`, then asserts:

- `<prefix>schema_version` exists
- it contains row `storage -> 1`

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.bootstrapsSchemaAndRequiredTables`
Expected: FAIL because the metadata table or row is missing.

- [ ] **Step 3: Write the failing test for legacy baseline**

Add an integration test that creates the legacy tables manually without version metadata, then constructs `PostgresStorageProvider` and asserts the schema-version row is inserted as `1`.

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.baselinesExistingLegacySchema`
Expected: FAIL because the provider does not baseline legacy schemas yet.

- [ ] **Step 5: Write the failing test for unsupported newer versions**

Add an integration test that inserts schema version `999` into the metadata table, then asserts provider construction fails with a message containing both the found and supported versions.

- [ ] **Step 6: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.rejectsUnsupportedNewerSchemaVersion`
Expected: FAIL because the provider currently ignores schema-version metadata.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "test: cover PostgreSQL schema migration bootstrap cases"
```

### Task 2: Implement schema version tracking and migration execution

**Files:**
- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java`

- [ ] **Step 1: Write the minimal migration model**

Refactor the current bootstrap SQL into an ordered migration list where version `1` contains the existing schema creation statements and final vector validation.

- [ ] **Step 2: Add metadata helpers**

Implement helpers to:

- create the schema-version table
- read the current version row
- upsert version metadata
- detect whether legacy application tables already exist

- [ ] **Step 3: Implement bootstrap reconciliation**

Update `bootstrap()` so it:

- creates schema + metadata table
- baselines a valid legacy unversioned schema to `1`
- applies missing migrations on fresh databases
- rejects stored versions greater than the supported version

- [ ] **Step 4: Run targeted bootstrap tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: PASS for migration metadata, legacy baseline, rollback, and vector drift coverage.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "feat: add PostgreSQL schema migration tracking"
```

## Chunk 2: Rollback Verification And Full Validation

### Task 3: Cover migration rollback behavior

**Files:**
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Write the failing rollback metadata test**

Extend the existing bootstrap-failure test so it also asserts `<prefix>schema_version` does not exist or has no `storage` row after rollback.

- [ ] **Step 2: Run the targeted rollback test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.rollsBackBootstrapWhenALaterStatementFails`
Expected: FAIL because rollback coverage does not yet check migration metadata.

- [ ] **Step 3: Adjust implementation only if rollback metadata is exposed**

Keep the production code minimal. Only change `PostgresSchemaManager` if the new rollback assertion exposes a real gap.

- [ ] **Step 4: Run targeted PostgreSQL tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java
git commit -m "test: verify PostgreSQL schema migration rollback"
```

### Task 4: Final verification and merge preparation

**Files:**
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-postgres-migrations-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-postgres-migrations.md`

- [ ] **Step 1: Update docs if implementation differs**

Keep the spec and plan aligned with the final code paths and test names.

- [ ] **Step 2: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Inspect git diff**

Run: `git status --short`
Expected: only migration-related files changed.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-03-15-java-lightrag-postgres-migrations-design.md docs/superpowers/plans/2026-03-15-java-lightrag-postgres-migrations.md src/main/java/io/github/lightragjava/storage/postgres/PostgresSchemaManager.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "docs: finalize PostgreSQL schema migration plan"
```
