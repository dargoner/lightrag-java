# Java LightRAG PostgreSQL Provider Concurrency Hardening Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden `PostgresStorageProvider` so top-level store operations, `writeAtomically(...)`, and `restore(...)` are serialized consistently within one JVM.

**Architecture:** Add a provider-level fair read/write lock, wrap top-level PostgreSQL store facades with lock-aware delegating stores, and keep transaction-bound atomic-view stores unchanged. Reuse the current JDBC stores rather than moving lock logic into every store implementation.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, AssertJ, PostgreSQL JDBC, HikariCP, Testcontainers PostgreSQL

---

## Prerequisites

- Start from a clean `main` worktree.
- Docker must be available for PostgreSQL Testcontainers.

## File Structure

Planned repository layout and file responsibilities:

- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

## Chunk 1: Locked Top-Level Facades

### Task 1: Add failing concurrency-oriented provider tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Add failing tests for locked top-level behavior**

Add tests for:

- stable top-level store instances remain stable after wrapping
- atomic-view stores are still distinct from top-level facades
- top-level writes and `restore(...)` do not interleave into torn state

- [ ] **Step 2: Run the targeted provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: FAIL because the provider does not yet coordinate top-level facades with restore and atomic writes.

- [ ] **Step 3: Commit the failing test scaffold if helpful**

Only commit if the test additions are easier to review separately; otherwise proceed directly to implementation.

### Task 2: Implement provider-level locking

**Files:**
- Modify: `src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Modify: `src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Add a fair provider-level read/write lock**

Requirements:

- use one `ReentrantReadWriteLock(true)`
- `writeAtomically(...)` holds the write lock for the entire operation
- `restore(...)` holds the write lock for the entire operation

- [ ] **Step 2: Add locked top-level store wrappers**

Requirements:

- wrap document/chunk/graph/vector stores exposed by provider getters
- read-only methods acquire read lock
- mutating top-level methods acquire write lock
- wrappers delegate to the existing JDBC stores

- [ ] **Step 3: Keep atomic-view stores unchanged**

Continue returning connection-bound JDBC stores from `newAtomicView(...)`, not the locked top-level wrappers.

- [ ] **Step 4: Re-run targeted provider tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.postgres.PostgresStorageProviderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java
git commit -m "fix: harden PostgreSQL provider concurrency"
```

## Chunk 2: Full Verification

### Task 3: Run the full test suite

**Files:**
- No additional file changes required unless a regression is found

- [ ] **Step 1: Run the full suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 2: If regressions appear, fix minimally and re-run**

- [ ] **Step 3: Final review**

Check:

```bash
git status --short
git log --oneline --decorate -10
```
