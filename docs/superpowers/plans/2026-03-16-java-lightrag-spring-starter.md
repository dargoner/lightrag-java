# Java LightRAG Spring Starter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a multi-module Spring Boot starter and a runnable demo app on top of the existing LightRAG SDK.

**Architecture:** Split the repository into `lightrag-core`, `lightrag-spring-boot-starter`, and `lightrag-spring-boot-demo`. Keep all existing SDK logic in core, add Spring auto-configuration in the starter, and prove the integration with a minimal REST demo.

**Tech Stack:** Java 17, Gradle multi-module build, Spring Boot 3.x, JUnit 5, AssertJ

---

## Chunk 1: Build Restructure

### Task 1: Convert to multi-module Gradle

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `lightrag-core/build.gradle.kts`
- Move/modify: current root `src/**` into `lightrag-core/src/**`

- [ ] **Step 1: Write failing build/test expectation**
- [ ] **Step 2: Restructure modules with the current SDK in `lightrag-core`**
- [ ] **Step 3: Run focused core tests**

## Chunk 2: Starter

### Task 2: Add Spring Boot starter auto-configuration

**Files:**
- Create: `lightrag-spring-boot-starter/build.gradle.kts`
- Create: `lightrag-spring-boot-starter/src/main/java/.../LightRagProperties.java`
- Create: `lightrag-spring-boot-starter/src/main/java/.../LightRagAutoConfiguration.java`
- Create: `lightrag-spring-boot-starter/src/test/java/...`

- [ ] **Step 1: Write failing starter context tests**
- [ ] **Step 2: Add properties + auto-configuration for `in-memory`**
- [ ] **Step 3: Add `postgres` and `postgres-neo4j` storage wiring**
- [ ] **Step 4: Run starter tests**

## Chunk 3: Demo

### Task 3: Add runnable Spring Boot demo

**Files:**
- Create: `lightrag-spring-boot-demo/build.gradle.kts`
- Create: `lightrag-spring-boot-demo/src/main/java/.../DemoApplication.java`
- Create: `lightrag-spring-boot-demo/src/main/java/.../DocumentController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/.../QueryController.java`
- Create: `lightrag-spring-boot-demo/src/test/java/...`

- [ ] **Step 1: Write failing demo integration tests**
- [ ] **Step 2: Add ingest and query endpoints**
- [ ] **Step 3: Add sample `application.yml`**
- [ ] **Step 4: Run demo tests**

## Chunk 4: Docs And Verification

### Task 4: Document Spring usage and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-spring-starter-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-spring-starter.md`

- [ ] **Step 1: Update README with starter and demo usage**
- [ ] **Step 2: Run focused verification for core + starter + demo**
- [ ] **Step 3: Inspect final diff**
