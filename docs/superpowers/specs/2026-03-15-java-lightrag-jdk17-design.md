# Java LightRAG JDK 17 Compatibility Design

## Overview

This document defines the compatibility migration from JDK 21 to JDK 17 for the Java LightRAG SDK.

The current repository builds and documents itself around JDK 21, but the actual language and library usage is already close to JDK 17 compatibility. The remaining work is mostly toolchain configuration, a small set of Java 21 collection API usages, and documentation cleanup.

## Goals

- Make the project build and test on JDK 17.
- Keep the public Java API unchanged.
- Avoid introducing any JDK 21-only language or library usage going forward.
- Update developer-facing docs and plans so they no longer advertise JDK 21 as the required baseline.

## Non-Goals

- Do not redesign the SDK architecture.
- Do not change query behavior, storage behavior, or model integrations.
- Do not introduce multi-release jars or separate source sets per JDK.
- Do not broaden support below JDK 17 in this phase.

## Approach Options

### Recommended: Direct JDK 17 Baseline Migration

Lower the Gradle toolchain to JDK 17, replace JDK 21-only API calls with JDK 17-compatible equivalents, and update the docs.

Benefits:

- smallest change set
- no runtime branching
- preserves current project structure and release shape

Trade-offs:

- requires checking the whole tree for Java 21-only APIs

### Alternative: Keep JDK 21 Build But Claim JDK 17 Runtime Compatibility

Compile on JDK 21 and avoid newer APIs where possible.

Benefits:

- minimal build-script change

Trade-offs:

- weak guarantee for users
- easy for later changes to silently break JDK 17 compatibility

### Alternative: Dual-Baseline Build

Add separate build paths for JDK 17 and JDK 21.

Benefits:

- explicit multi-version coverage

Trade-offs:

- too much complexity for the current codebase
- unnecessary when the code can simply target JDK 17 directly

## Recommended Design

Adopt JDK 17 as the single build and runtime baseline.

### Build Configuration

Update the Gradle Java toolchain from 21 to 17 in `build.gradle.kts`.

The test suite should continue to run through the same `./gradlew test` entrypoint. No extra Gradle source-set or compatibility layer is needed.

### Source Compatibility Changes

Replace Java 21-only collection convenience methods with JDK 17-safe access patterns.

Known current changes:

- replace `List.getFirst()` with indexed access after existing non-empty checks

The migration should be conservative:

- no opportunistic refactoring
- no behavior changes
- no widening of nullability or error handling

### Documentation Changes

Update user-facing and planning docs that currently say JDK 21 / Java 21:

- `README.md`
- existing plan docs under `docs/superpowers/plans/`

The docs should consistently state:

- JDK 17 is supported
- Gradle can auto-provision the matching JDK 17 toolchain when needed

## Testing Strategy

Required verification:

- targeted tests for any files changed for JDK 17 compatibility
- full `./gradlew test`

Success criteria:

- project compiles under the Gradle JDK 17 toolchain
- all tests pass without behavioral regressions
