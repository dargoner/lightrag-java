# README Release Section Cleanup Design

**Goal:** Simplify the release-related sections in both README files so they describe only the supported GitHub Actions release flow, including normal releases and patch/security releases.

## Scope

- Remove local manual Maven Central publishing instructions from `README.md`.
- Add the same streamlined release guidance to `README_zh.md`.
- Document how to trigger a patch/security release that only bumps the patch version, such as `0.2.0` -> `0.2.1`.

## Design

### Shared structure

Both README files will keep only four release topics:

1. Published coordinates
2. Standard release by pushing a `vX.Y.Z` tag
3. Patch/security release by branching from an existing tag and pushing a patch tag
4. Automatic version bump behavior on `main`

### Standard release

- Release is triggered by the GitHub Actions `Release` workflow.
- Normal release path: push `v0.3.0` from the desired commit.

### Patch/security release

- Create a hotfix branch from the released tag, for example `v0.2.0`.
- Apply the fix.
- Tag the fix commit with `v0.2.1`.
- Push the tag to trigger the same `Release` workflow.

### Automatic version bump note

- Successful release from `main` advances the repository default version to the next minor snapshot.
- Patch releases still use the patch tag version, while `main` remains on the next minor snapshot unless a new change is needed.
