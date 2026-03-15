# PostgreSQL Advisory Locks Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PostgreSQL advisory-lock coordination so `PostgresStorageProvider` preserves its read/write consistency guarantees across multiple JVM instances.

**Architecture:** `PostgresStorageProvider` will keep its current local `ReentrantReadWriteLock`, and layer a dedicated `PostgresAdvisoryLockManager` on top to acquire shared locks for reads and exclusive locks for writes, atomic writes, and restores. Advisory locks will use a separate Hikari pool so lock sessions never starve the pool needed for real store operations.

**Tech Stack:** Java 17, JDBC, HikariCP, PostgreSQL advisory locks, Testcontainers, JUnit 5, AssertJ

---

## Chunk 1: Cross-Provider Lock Semantics

### Task 1: Add failing cross-provider concurrency tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Write the failing test for cross-provider read blocking**

Add an integration test that:

- creates two `PostgresStorageProvider` instances with the same config
- starts `providerA.writeAtomically(...)` and blocks it with a latch
- calls `providerB.documentStore().load(...)`
- asserts the read does not finish before the write is released

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.crossProviderReadsWaitForAtomicWriteToFinish`
Expected: FAIL because current locking is only in-process.

- [ ] **Step 3: Write the failing test for cross-provider writer serialization**

Add an integration test that:

- creates two providers with the same config
- blocks `providerA.writeAtomically(...)`
- starts `providerB.writeAtomically(...)`
- asserts the second writer does not complete before the first is released

- [ ] **Step 4: Run the targeted test to verify it fails**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.crossProviderWritesSerialize`
Expected: FAIL because different provider instances do not currently coordinate.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "test: cover PostgreSQL advisory lock semantics"
```

### Task 2: Implement advisory lock manager and provider integration

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/postgres/PostgresAdvisoryLockManager.java`
- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`

- [ ] **Step 1: Implement the lock manager**

Add a backend-local helper that:

- derives a stable 64-bit lock key from schema + table prefix
- acquires/releases shared advisory locks
- acquires/releases exclusive advisory locks
- wraps suppliers/runnables with lock lifecycle and failure handling

- [ ] **Step 2: Integrate a dedicated lock pool into the provider**

Update `PostgresStorageProvider` to:

- create a second Hikari pool for lock sessions
- build the advisory lock manager from that pool
- close the lock pool when the provider closes

- [ ] **Step 3: Wrap provider operations with advisory locks**

Update:

- top-level read facades to use shared advisory locks
- top-level write facades to use exclusive advisory locks
- `writeAtomically(...)` to use exclusive advisory lock
- `restore(...)` to use exclusive advisory lock

- [ ] **Step 4: Run targeted provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: PASS for cross-provider advisory locking plus existing provider regression coverage.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresAdvisoryLockManager.java src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "feat: add PostgreSQL advisory lock coordination"
```

## Chunk 2: Documentation And Final Verification

### Task 3: Align docs and verify full suite

**Files:**
- Modify: `docs/superpowers/specs/2026-03-15-java-lightrag-postgres-advisory-locks-design.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-postgres-advisory-locks.md`

- [ ] **Step 1: Update docs if implementation differs**

Keep the spec and plan aligned with the final lock-manager and provider structure.

- [ ] **Step 2: Run full verification**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Inspect git diff**

Run: `git status --short`
Expected: only advisory-lock-related files changed.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-03-15-java-lightrag-postgres-advisory-locks-design.md docs/superpowers/plans/2026-03-15-java-lightrag-postgres-advisory-locks.md src/main/java/io/github/lightragjava/storage/postgres/PostgresAdvisoryLockManager.java src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "docs: finalize PostgreSQL advisory lock plan"
```
