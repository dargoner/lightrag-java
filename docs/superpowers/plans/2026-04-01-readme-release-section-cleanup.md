# README Release Section Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the release guidance in both README files and document standard plus patch/security release triggers.

**Architecture:** Replace verbose local publishing instructions with a short GitHub Actions-centered release section in English and Chinese. Keep the artifact coordinates, standard tag release flow, hotfix patch release flow, and automatic version bump behavior.

**Tech Stack:** Markdown, GitHub Actions release flow, semantic versioning.

---

### Task 1: Simplify English Release Section

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace manual local publishing details**

Keep only:
- published artifacts
- GitHub Actions release trigger
- standard tag release example
- patch/security release example
- automatic version bump note

### Task 2: Simplify Chinese Release Section

**Files:**
- Modify: `README_zh.md`

- [ ] **Step 1: Add matching Chinese release section**

Add a concise section with the same structure:
- artifact coordinates
- normal release trigger
- patch/security release trigger
- automatic version bump note

### Task 3: Verify

**Files:**
- Verify: `README.md`
- Verify: `README_zh.md`

- [ ] **Step 1: Check patch sanity**
Run: `git diff --check`

- [ ] **Step 2: Verify release keywords**
Run: `rg -n "Publish|Publishing|Release workflow|Patch|安全修复|补丁|v0\\.2\\.1|0\\.3\\.0-SNAPSHOT" README.md README_zh.md -S`

- [ ] **Step 3: Check worktree state**
Run: `git status --short`
