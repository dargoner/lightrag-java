# Java LightRAG RAGAS Evaluation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reuse the upstream RAGAS evaluation style with `lightrag-java` by replacing the Python server dependency with a local Java SDK query runner.

**Architecture:** Add a Java evaluation service and CLI for `answer + contexts`, wire a Gradle `JavaExec` task to run it, then add a Python RAGAS script plus sample assets and env documentation.

**Tech Stack:** Java 17, Gradle, Python 3, RAGAS, Jackson

---

## Chunk 1: Java Runner

### Task 1: Add the Java evaluation runner

**Files:**
- Create: `src/main/java/io/github/lightragjava/evaluation/RagasEvaluationService.java`
- Create: `src/main/java/io/github/lightragjava/evaluation/RagasEvaluationCli.java`
- Modify: `build.gradle.kts`
- Test: `src/test/java/io/github/lightragjava/evaluation/RagasEvaluationServiceTest.java`

- [x] **Step 1: Write the failing service test**
- [x] **Step 2: Run it to verify red**
- [x] **Step 3: Implement document loading + query execution**
- [x] **Step 4: Add CLI + Gradle JavaExec task**
- [x] **Step 5: Re-run the focused Java test**

## Chunk 2: Python RAGAS Adapter

### Task 2: Add the upstream-style evaluation script

**Files:**
- Create: `evaluation/ragas/eval_rag_quality_java.py`
- Create: `evaluation/ragas/requirements.txt`
- Create: `evaluation/ragas/.env.example`
- Create: `evaluation/ragas/README.md`
- Create: `evaluation/ragas/sample_dataset.json`
- Create: `evaluation/ragas/sample_documents/*.md`

- [x] **Step 1: Reuse upstream-style dataset + sample docs**
- [x] **Step 2: Adapt generation path from HTTP to Java CLI subprocess**
- [x] **Step 3: Document required environment variables**

## Chunk 3: Verification

### Task 3: Verify the adapter layer

**Files:**
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-ragas-eval-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-ragas-eval.md`

- [ ] **Step 1: Run focused Java test**

Run:

- `./gradlew test --tests io.github.lightragjava.evaluation.RagasEvaluationServiceTest`

- [ ] **Step 2: Python-parse the evaluation script**

Run:

- `python3 -m py_compile evaluation/ragas/eval_rag_quality_java.py`

- [ ] **Step 3: Inspect final diff**

Run:

- `git status --short`
