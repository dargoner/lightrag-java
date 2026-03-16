# Java LightRAG PG Neo4j RAGAS Evaluation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the existing upstream-style RAGAS evaluation against a production-like `PostgresNeo4jStorageProvider` profile using Testcontainers, while preserving the current in-memory evaluation path.

**Architecture:** Add a batch-oriented Java evaluation runner with switchable storage profiles. Reuse the same dataset and Python RAGAS scoring logic, but move provider lifetime to one full evaluation run instead of one process per query.

**Tech Stack:** Java 17, Gradle, JUnit 5, Jackson, Testcontainers, Python 3, RAGAS

---

## Chunk 1: Red Tests

### Task 1: Add failing batch-evaluation tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/evaluation/RagasEvaluationServiceTest.java`
- Create: `src/test/java/io/github/lightragjava/evaluation/RagasBatchEvaluationServiceTest.java`

- [ ] **Step 1: Add failing batch in-memory evaluation test**

Cover:

- documents ingest once
- multiple dataset questions return multiple results

- [ ] **Step 2: Add failing Postgres Neo4j profile test**

Cover:

- one `postgres-neo4j-testcontainers` profile can ingest docs and answer at least one query

- [ ] **Step 3: Run focused tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.evaluation.RagasEvaluationServiceTest --tests io.github.lightragjava.evaluation.RagasBatchEvaluationServiceTest`

Expected: FAIL because batch/profile support does not exist yet.

## Chunk 2: Green Implementation

### Task 2: Add batch evaluation runner and storage profiles

**Files:**
- Modify: `src/main/java/io/github/lightragjava/evaluation/RagasEvaluationService.java`
- Create: `src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationService.java`
- Modify: `src/main/java/io/github/lightragjava/evaluation/RagasEvaluationCli.java`
- Modify: `evaluation/ragas/eval_rag_quality_java.py`
- Modify: `evaluation/ragas/README.md`

- [ ] **Step 1: Add batch Java service**

Requirements:

- load dataset JSON
- ingest docs once
- run all questions sequentially
- return batch JSON

- [ ] **Step 2: Add storage profile abstraction**

Requirements:

- support `in-memory`
- support `postgres-neo4j-testcontainers`
- cleanly close provider and containers after the run

- [ ] **Step 3: Update CLI and Python adapter**

Requirements:

- pass `storage-profile`
- switch Python script to one batch subprocess per run

- [ ] **Step 4: Run focused tests**

Run:

- `./gradlew test --tests io.github.lightragjava.evaluation.RagasEvaluationServiceTest --tests io.github.lightragjava.evaluation.RagasBatchEvaluationServiceTest`

Expected: PASS.

## Chunk 3: End-to-End Evaluation

### Task 3: Run the production-like evaluation

**Files:**
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-ragas-pgneo4j-eval-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-ragas-pgneo4j-eval.md`

- [ ] **Step 1: Re-run baseline in-memory batch mode**

- [ ] **Step 2: Run `postgres-neo4j-testcontainers` batch mode**

- [ ] **Step 3: Compare RAGAS metrics between in-memory and PG/Neo4j**
