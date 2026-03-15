# Java LightRAG PostgreSQL Provider Concurrency Hardening Design

## Overview

This document defines the next hardening phase for the PostgreSQL durable provider.

`PostgresStorageProvider` currently gives transactional atomicity for `writeAtomically(...)`, but its top-level JDBC store facades are not coordinated with `restore(Snapshot)` or provider-wide atomic writes. That leaves a concurrency hole: a caller can issue normal reads or writes while `restore(...)` is truncating and rebuilding tables, and the provider can expose torn state or lose externally visible writes.

The in-memory provider already protects this class of problem with a shared read/write lock. The PostgreSQL provider should adopt the same high-level model while preserving its current SPI and transaction boundaries.

## Goals

- Serialize `writeAtomically(...)` and `restore(Snapshot)` against all top-level store operations.
- Preserve current public APIs and store SPI types.
- Keep JDBC transaction behavior unchanged inside provider-owned atomic writes.
- Prevent users from observing partial state during `restore(...)`.

## Non-Goals

- Do not redesign the storage SPI.
- Do not introduce cross-process distributed locks in this phase.
- Do not add a migration framework or schema versioning in this phase.
- Do not change query behavior or storage layout.

## Approach Options

### Recommended: Provider-Level Read/Write Lock With Locked Store Facades

Add one fair `ReadWriteLock` to `PostgresStorageProvider` and expose top-level document/chunk/graph/vector store wrappers that acquire:

- read lock for read operations
- write lock for top-level save operations

`writeAtomically(...)` and `restore(...)` take the same write lock for their full duration.

Benefits:

- closely matches the proven in-memory provider pattern
- keeps implementation local to the provider
- avoids changing the underlying JDBC store implementations

Trade-offs:

- only protects concurrency inside one JVM instance
- adds small lock overhead to top-level store access

### Alternative: Lock Only `writeAtomically(...)` And `restore(...)`

Protect just provider-owned atomic writes and restore.

Benefits:

- smallest code change

Trade-offs:

- still allows top-level saves to race with `restore(...)`
- incomplete for callers that use store facades directly

### Alternative: Push Locking Into Every JDBC Store

Make each PostgreSQL store manage its own lock.

Benefits:

- lock logic sits next to store operations

Trade-offs:

- harder to coordinate cross-store restore semantics
- duplicates lock policy across stores

## Recommended Design

Adopt a provider-level fair `ReadWriteLock` and lock-aware top-level store wrappers.

### Provider Behavior

`PostgresStorageProvider` should:

- create one fair `ReentrantReadWriteLock`
- expose stable top-level store wrappers that delegate into the existing JDBC stores
- hold the write lock across the full `writeAtomically(...)` call
- hold the write lock across the full `restore(...)` call

### Store Wrapper Behavior

Top-level wrappers should use:

- read lock for `load`, `list`, `contains`, `search`, and other read-only operations
- write lock for `save` / `saveAll`

The wrappers should not own JDBC transactions themselves. They only serialize visibility and coordinate with provider-level writes and restore.

### Atomic View Behavior

Stores exposed inside `writeAtomically(...)` should remain distinct transaction-bound JDBC stores, as they are today. They do not need additional per-method locking because the provider already holds the write lock while the atomic operation runs.

### Restore Semantics

While `restore(...)` runs:

- no top-level reads should observe partially truncated state
- no top-level writes should interleave with the restore

This gives the PostgreSQL provider the same user-visible consistency model already present in the in-memory provider.

## Testing Strategy

Required coverage:

- top-level store getters still return stable facade instances
- atomic-view stores remain distinct from top-level facades
- reads block or serialize cleanly around `restore(...)`
- top-level writes do not interleave with `restore(...)`
- existing PostgreSQL provider tests continue to pass

Verification should include:

- targeted `PostgresStorageProviderTest`
- full `./gradlew test`
