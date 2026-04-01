# Hotfix Release No-Main-Bump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure patch/security releases do not automatically change the default development version on `main`.

**Architecture:** Add a guard step to the `Release` workflow that compares the released commit SHA with the current default branch head SHA. Only if they match should the `bump-next-snapshot` job edit `gradle.properties`. Update README wording to reflect the rule.

**Tech Stack:** GitHub Actions, Bash, Markdown.

---

### Task 1: Guard Automatic Version Bump

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Expose release commit SHA to the bump job**

Use the publish job to expose the released commit SHA as a job output.

- [ ] **Step 2: Compare against default branch head**

In the bump job:
- resolve the default branch head SHA
- compare it to the released SHA
- skip bump when they differ

### Task 2: Update README Wording

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: Clarify patch release behavior**

Document that patch/security releases from a hotfix branch do not change `main`.

### Task 3: Verify

**Files:**
- Verify: `.github/workflows/release.yml`
- Verify: `README.md`
- Verify: `README_zh.md`

- [ ] **Step 1: Check patch sanity**
Run: `git diff --check`

- [ ] **Step 2: Parse workflow YAML**
Run: `python3 -c 'import yaml; yaml.safe_load(open(\".github/workflows/release.yml\")); print(\"yaml-ok\")'`

- [ ] **Step 3: Verify guard wording**
Run: `rg -n "released_sha|default branch head|does not change main|不会改 main" -S .github/workflows/release.yml README.md README_zh.md`
