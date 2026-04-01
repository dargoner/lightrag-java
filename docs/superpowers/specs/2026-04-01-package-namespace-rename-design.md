# Java Package Namespace Rename Design

## Context

The repository currently uses `io.github.lightragjava` as the Java package root across:

- `lightrag-core` production and test sources
- `lightrag-spring-boot-starter` production and test sources
- `lightrag-spring-boot-demo` production and test sources
- Gradle `mainClass` references
- runtime-facing docs and commands

The desired target is `io.github.lightrag`.

There are already uncommitted local changes under the existing package tree. The rename must preserve those edits and migrate them together instead of reverting or overwriting them.

## Goal

Rename the active Java package namespace from `io.github.lightragjava` to `io.github.lightrag` in executable code and build/runtime entry points, while keeping the worktree consistent and compilable.

## Non-Goals

- Do not introduce a compatibility shim that keeps both package roots alive.
- Do not mass-edit historical design docs just to remove old package names.
- Do not change Maven coordinates, artifact ids, or GitHub repository naming as part of this task.

## Approach Options

### Option 1: Full Java namespace migration

Rename package directories and update package/import references across source sets, Gradle entry points, and runtime-facing docs.

Pros:

- Clean end state
- No duplicate APIs
- Matches the requested namespace directly

Cons:

- Broad diff touching many files
- Requires verification across modules

### Option 2: Source-only migration

Rename only Java source/test packages and Gradle entry points, leaving docs untouched.

Pros:

- Smaller diff
- Faster to execute

Cons:

- Leaves visible drift in README and run commands

### Option 3: Transitional alias packages

Introduce `io.github.lightrag` alongside `io.github.lightragjava`.

Pros:

- Temporary compatibility

Cons:

- Duplicate maintenance surface
- Adds complexity without user value

## Decision

Use Option 1 with a narrow documentation scope:

- migrate Java production and test sources
- migrate Gradle `mainClass` references
- update runtime-facing docs and commands that users are likely to copy
- leave historical design/plan docs untouched unless they are part of active runtime instructions

This gives a clean package rename without creating a noisy documentation-only churn across archived specs.

## Implementation Outline

1. Rename Java package directory trees from `io/github/lightragjava` to `io/github/lightrag`.
2. Update `package` declarations and all `import` / fully-qualified references.
3. Update Gradle entry points that reference old fully-qualified main classes.
4. Update README and other active docs that expose runnable class names or test commands.
5. Verify no remaining `io.github.lightragjava` references in active code/build paths.
6. Run targeted compilation/tests as allowed by the local environment.

## Risks and Mitigations

### Risk: stale string references remain outside package declarations

Mitigation:

- run repository-wide searches for `io.github.lightragjava`
- distinguish active runtime/build references from historical docs

### Risk: current uncommitted changes get lost during directory rename

Mitigation:

- perform moves within the existing worktree
- never reset or revert unrelated edits
- verify `git diff` after moves

### Risk: verification is partially blocked by environment limits

Mitigation:

- always run available static verification
- report any blocked checks explicitly, especially Docker/Testcontainers or Gradle distribution issues

## Verification Plan

- `rg -n "io\\.github\\.lightragjava" -S .`
- targeted Java compilation or Gradle module tests where the environment permits
- `git diff --check`

## Success Criteria

- active Java source and test code use `io.github.lightrag`
- Gradle entry points no longer reference `io.github.lightragjava`
- no active build/runtime references remain under the old package root
- existing local edits remain intact after the rename
