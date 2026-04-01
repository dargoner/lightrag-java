# Java Package Namespace Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the active Java package namespace from `io.github.lightragjava` to `io.github.lightrag` across code, tests, build entry points, and runtime-facing docs without losing current local edits.

**Architecture:** This is a repository-wide namespace migration. Execute it in four passes: move Java package directories, rewrite package/import references, update build/runtime string references, then run repository-wide scans plus the available compile/test checks. Preserve existing uncommitted edits by moving the current files in place instead of recreating them.

**Tech Stack:** Java 17, Gradle multi-module build, Spring Boot, ripgrep, git, shell-based refactoring, JUnit/Testcontainers verification where available.

---

### Task 1: Move Java Source Trees To The New Package Root

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/**`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/**`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/**`
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/**`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/**`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/**`

- [ ] **Step 1: Inspect current package directory layout**

Run: `find lightrag-core/src/main/java/io/github -maxdepth 2 -type d | sort`
Run: `find lightrag-spring-boot-starter/src/main/java/io/github -maxdepth 2 -type d | sort`
Run: `find lightrag-spring-boot-demo/src/main/java/io/github -maxdepth 2 -type d | sort`
Expected: `.../lightragjava` exists in each active module that owns Java sources.

- [ ] **Step 2: Create target package roots before moving files**

Run: `mkdir -p lightrag-core/src/main/java/io/github/lightrag`
Run: `mkdir -p lightrag-core/src/test/java/io/github/lightrag`
Run: `mkdir -p lightrag-spring-boot-starter/src/main/java/io/github/lightrag`
Run: `mkdir -p lightrag-spring-boot-starter/src/test/java/io/github/lightrag`
Run: `mkdir -p lightrag-spring-boot-demo/src/main/java/io/github/lightrag`
Run: `mkdir -p lightrag-spring-boot-demo/src/test/java/io/github/lightrag`
Expected: destination roots exist and are empty or ready to receive moved files.

- [ ] **Step 3: Move package directory contents in place**

Run: `mv lightrag-core/src/main/java/io/github/lightragjava/* lightrag-core/src/main/java/io/github/lightrag/`
Run: `mv lightrag-core/src/test/java/io/github/lightragjava/* lightrag-core/src/test/java/io/github/lightrag/`
Run: `mv lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/* lightrag-spring-boot-starter/src/main/java/io/github/lightrag/`
Run: `mv lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/* lightrag-spring-boot-starter/src/test/java/io/github/lightrag/`
Run: `mv lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/* lightrag-spring-boot-demo/src/main/java/io/github/lightrag/`
Run: `mv lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/* lightrag-spring-boot-demo/src/test/java/io/github/lightrag/`
Expected: Java source files now live under `io/github/lightrag/...`, including previously uncommitted edits.

- [ ] **Step 4: Remove now-empty old package directories**

Run: `find lightrag-core lightrag-spring-boot-starter lightrag-spring-boot-demo -type d -path '*/io/github/lightragjava' -empty -delete`
Expected: no empty `io/github/lightragjava` directories remain under active Java source sets.

- [ ] **Step 5: Verify the move before content edits**

Run: `find lightrag-core lightrag-spring-boot-starter lightrag-spring-boot-demo -type d -path '*/io/github/lightragjava'`
Expected: no output from active module source/test trees.

### Task 2: Rewrite Package Declarations And Imports

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/**`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/**`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/**`
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/**`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/**`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/**`

- [ ] **Step 1: Write the failing detection query**

Run: `rg -n "io\\.github\\.lightragjava" lightrag-core/src lightrag-spring-boot-starter/src lightrag-spring-boot-demo/src -S`
Expected: many matches across `package`, `import`, fully-qualified references, and tests.

- [ ] **Step 2: Rewrite all Java source references to the new namespace**

Run: `find lightrag-core/src lightrag-spring-boot-starter/src lightrag-spring-boot-demo/src -name '*.java' -print0 | xargs -0 perl -0pi -e 's/io\\.github\\.lightragjava/io.github.lightrag/g'`
Expected: Java files now reference `io.github.lightrag`.

- [ ] **Step 3: Re-run the detection query to verify source rewrite completeness**

Run: `rg -n "io\\.github\\.lightragjava" lightrag-core/src lightrag-spring-boot-starter/src lightrag-spring-boot-demo/src -S`
Expected: no output, or only non-Java artifacts if some generated file appears unexpectedly.

- [ ] **Step 4: Spot-check package declarations**

Run: `sed -n '1,5p' lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
Run: `sed -n '1,5p' lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java`
Run: `sed -n '1,5p' lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DemoApplication.java`
Expected: each file starts with `package io.github.lightrag...`.

### Task 3: Update Build Entrypoints And Runtime-Facing Docs

**Files:**
- Modify: `lightrag-core/build.gradle.kts`
- Modify: `README.md`
- Modify: `README_zh.md`
- Modify: runtime-facing docs or commands discovered by search results

- [ ] **Step 1: Find non-source references to the old namespace**

Run: `rg -n "io\\.github\\.lightragjava" lightrag-core/build.gradle.kts README.md README_zh.md docs AGENTS.md evaluation -S`
Expected: Gradle main class strings, command examples, and some historical docs.

- [ ] **Step 2: Update active build/runtime references**

Run: `perl -0pi -e 's/io\\.github\\.lightragjava/io.github.lightrag/g' lightrag-core/build.gradle.kts README.md README_zh.md`
Expected: build entry points and README command examples now use `io.github.lightrag`.

- [ ] **Step 3: Update only active docs that users are likely to execute directly**

Run: `rg -n "io\\.github\\.lightragjava" README.md README_zh.md docs/superpowers/plans docs/superpowers/specs evaluation/real_converted_markdown/REPORT.md -S`
Expected: review remaining matches and change only files that serve as active run/test instructions for current users.

- [ ] **Step 4: Keep historical docs intentionally unchanged when they are archival**

Run: `git diff -- README.md README_zh.md lightrag-core/build.gradle.kts docs/superpowers/plans docs/superpowers/specs`
Expected: runtime-facing references are updated, while archived design history is not mass-rewritten without need.

### Task 4: Verify Namespace Migration And Preserve Existing Work

**Files:**
- Verify: repository-wide namespace references
- Verify: current worktree state
- Verify: module compilation/tests as environment allows

- [ ] **Step 1: Run repository-wide namespace scan**

Run: `rg -n "io\\.github\\.lightragjava" -S .`
Expected: only intentionally preserved historical references remain; active code/build/runtime files should not appear.

- [ ] **Step 2: Run whitespace and patch sanity check**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 3: Run targeted compile verification for active code**

Run: `javac --release 17 -cp "$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | paste -sd: -)" -sourcepath lightrag-core/src/main/java -d /tmp/lightrag-javac-main $(find lightrag-core/src/main/java -name '*.java')`
Expected: compile success, or environment-specific warnings only.

- [ ] **Step 4: Run targeted compile verification for starter/demo sources**

Run: `javac --release 17 -cp "$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | paste -sd: -):/tmp/lightrag-javac-main" -sourcepath lightrag-spring-boot-starter/src/main/java:lightrag-spring-boot-demo/src/main/java -d /tmp/lightrag-javac-app $(find lightrag-spring-boot-starter/src/main/java lightrag-spring-boot-demo/src/main/java -name '*.java')`
Expected: compile success, or a clearly reported missing-environment dependency if the local cache is incomplete.

- [ ] **Step 5: Run targeted tests if the environment supports them**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"`
Expected: pass if Gradle distribution and Docker/test prerequisites are available; otherwise capture the exact blocker and report it.

- [ ] **Step 6: Review worktree to ensure existing local edits survived**

Run: `git status --short`
Expected: renamed files plus the earlier in-progress PostgreSQL retry changes still exist under the new package root, with no lost edits.
