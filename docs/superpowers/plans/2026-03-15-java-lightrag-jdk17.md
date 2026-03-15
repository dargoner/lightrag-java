# Java LightRAG JDK 17 Compatibility Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lower the Java LightRAG SDK baseline from JDK 21 to JDK 17 without changing public behavior.

**Architecture:** Keep the current single-module Gradle project shape. Update the Gradle toolchain, replace the small number of Java 21-only APIs with JDK 17-compatible code, and align repository docs with the new baseline.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, AssertJ, Jackson, OkHttp, PostgreSQL JDBC, HikariCP, pgvector-java, Neo4j Java Driver, Testcontainers

---

## Prerequisites

- The repository must start from a clean `main` worktree.
- Gradle toolchain provisioning must remain enabled so `./gradlew test` can fetch JDK 17 if needed.

## File Structure

Planned repository layout and file responsibilities:

- Modify: `build.gradle.kts`
- Modify: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-sdk.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-production-storage.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-neo4j-graph.md`

## Chunk 1: Toolchain And Source Compatibility

### Task 1: Lower the Gradle toolchain to JDK 17

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write the failing compatibility check**

Confirm the current build file still targets Java 21 and therefore does not satisfy the JDK 17 requirement.

- [ ] **Step 2: Change the toolchain**

Update:

```kotlin
languageVersion.set(JavaLanguageVersion.of(17))
```

- [ ] **Step 3: Run targeted compilation**

Run: `./gradlew compileJava compileTestJava`
Expected: FAIL if Java 21-only APIs remain in the codebase.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: target JDK 17"
```

### Task 2: Replace Java 21-only source usages

**Files:**
- Modify: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`

- [ ] **Step 1: Write the failing compilation verification**

Run: `./gradlew compileJava`
Expected: FAIL on `List.getFirst()` because the code now targets JDK 17.

- [ ] **Step 2: Apply minimal source changes**

Replace `getFirst()` calls with indexed access that preserves the current assumptions:

- `embedAll(...).get(0)`

- [ ] **Step 3: Re-run targeted tests**

Run: `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java src/main/java/io/github/lightragjava/query/MixQueryStrategy.java
git commit -m "fix: align query code with JDK 17"
```

## Chunk 2: Documentation And Full Verification

### Task 3: Update docs to JDK 17

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-sdk.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-production-storage.md`
- Modify: `docs/superpowers/plans/2026-03-15-java-lightrag-neo4j-graph.md`

- [ ] **Step 1: Update user-facing and planning docs**

Replace JDK 21 references with JDK 17 wording while keeping the existing intent intact.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/plans/2026-03-15-java-lightrag-sdk.md docs/superpowers/plans/2026-03-15-java-lightrag-production-storage.md docs/superpowers/plans/2026-03-15-java-lightrag-neo4j-graph.md
git commit -m "docs: update JDK 17 compatibility guidance"
```
