# Auto Bump Next Minor Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically advance the repository default version to the next minor snapshot after a successful formal release.

**Architecture:** Move the default development version into `gradle.properties`, keep release publishing driven by `releaseVersion`, and extend the GitHub release workflow with a post-release bump job that edits `gradle.properties` on the default branch.

**Tech Stack:** Gradle Kotlin DSL, GitHub Actions, Bash.

---

### Task 1: Move Default Version Source Into `gradle.properties`

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add repository default version property**

Add a line like:

```properties
projectVersion=0.1.0-SNAPSHOT
```

- [ ] **Step 2: Update root build version resolution**

Change version resolution to read:

```kotlin
version =
    providers.gradleProperty("releaseVersion")
        .orElse(providers.gradleProperty("projectVersion"))
        .orElse("0.1.0-SNAPSHOT")
        .get()
```

### Task 2: Extend Release Workflow

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Allow workflow to push version bump commits**

Set:

```yaml
permissions:
  contents: write
```

- [ ] **Step 2: Expose resolved release version from publish job**

Use the existing version-resolution step output as a job output for downstream jobs.

- [ ] **Step 3: Add a post-publish bump job**

Create a `bump-next-snapshot` job that:
- depends on `publish`
- checks out the default branch
- parses `needs.publish.outputs.release_version`
- computes `next_minor_snapshot`
- updates `gradle.properties`
- commits and pushes only when the file changed

### Task 3: Verify

**Files:**
- Verify: `build.gradle.kts`
- Verify: `gradle.properties`
- Verify: `.github/workflows/release.yml`

- [ ] **Step 1: Check workflow and patch sanity**
Run: `git diff --check`

- [ ] **Step 2: Parse workflow YAML**
Run: `python3 -c 'import yaml; yaml.safe_load(open(\".github/workflows/release.yml\")); print(\"yaml-ok\")'`

- [ ] **Step 3: Verify version references**
Run: `rg -n "projectVersion|releaseVersion|next minor|contents: write|bump-next-snapshot" -S build.gradle.kts gradle.properties .github/workflows/release.yml`

- [ ] **Step 4: Check worktree state**
Run: `git status --short`
