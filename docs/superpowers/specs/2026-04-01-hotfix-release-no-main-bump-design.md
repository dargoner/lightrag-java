# Hotfix Release No-Main-Bump Design

**Goal:** Prevent patch/security releases from automatically bumping the `main` development version.

## Scope

- Update the `Release` workflow so automatic version bump happens only for releases created from the current `main` head commit.
- Keep patch/security releases fully supported.
- Clarify the behavior in both README files.

## Design

### Bump eligibility

The workflow should bump `main` only when the released commit SHA equals the current default branch head SHA.

That gives the desired behavior:

- release from `main` head: bump `main`
- release from an older tag or hotfix branch: do not touch `main`

### Why this rule

- It is branch-agnostic and works for both tag pushes and manual workflow dispatch.
- It does not depend on branch naming conventions like `hotfix/*`.
- It prevents a `v0.2.1` hotfix release from mutating `main`, which should remain on `0.3.0-SNAPSHOT`.

### Documentation

The README release section should explicitly state:

- standard release from `main` advances `main` to the next minor snapshot
- patch/security release from a hotfix branch does not change `main`
