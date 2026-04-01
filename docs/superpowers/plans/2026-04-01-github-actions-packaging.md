# GitHub Actions Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GitHub Actions automation for CI packaging and Maven Central publishing, and make signing work with passphrase-free in-memory PGP keys.

**Architecture:** Use two repository workflows. `ci.yml` handles continuous build verification. `release.yml` handles tagged or manual Maven Central publication using repository secrets. Update Gradle module build files so signing activates when an in-memory key is present even if no password is provided.

**Tech Stack:** GitHub Actions, Gradle, Java 17, Vanniktech Maven Publish plugin.

---

### Task 1: Add CI Workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the CI workflow**

Create a workflow that:
- runs on `push` to `main`
- runs on all `pull_request`
- sets up Temurin 17
- enables Gradle cache
- runs `./gradlew --no-daemon build`

### Task 2: Add Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Add the release workflow**

Create a workflow that:
- runs on tags matching `v*`
- supports `workflow_dispatch` with `release_version`
- resolves the final version from tag or input
- passes Maven Central and signing secrets as `ORG_GRADLE_PROJECT_*`
- publishes `lightrag-core` and `lightrag-spring-boot-starter`

### Task 3: Support Passphrase-Free In-Memory Signing

**Files:**
- Modify: `lightrag-core/build.gradle.kts`
- Modify: `lightrag-spring-boot-starter/build.gradle.kts`

- [ ] **Step 1: Relax signing config detection**

Enable signing whenever either:
- `signingInMemoryKey` exists, or
- legacy keyring properties exist

This preserves legacy support while allowing empty-passphrase keys.

### Task 4: Verify

**Files:**
- Verify: `.github/workflows/ci.yml`
- Verify: `.github/workflows/release.yml`
- Verify: `lightrag-core/build.gradle.kts`
- Verify: `lightrag-spring-boot-starter/build.gradle.kts`

- [ ] **Step 1: Check staged changes**
Run: `git diff --check`

- [ ] **Step 2: Validate signing references**
Run: `rg -n "signingInMemoryKey|signingInMemoryKeyPassword|publishAndReleaseToMavenCentral|workflow_dispatch|ORG_GRADLE_PROJECT_" -S .github/workflows lightrag-core/build.gradle.kts lightrag-spring-boot-starter/build.gradle.kts`

- [ ] **Step 3: Confirm worktree state**
Run: `git status --short`
