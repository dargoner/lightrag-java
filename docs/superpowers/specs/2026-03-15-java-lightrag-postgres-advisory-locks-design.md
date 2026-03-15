# Java LightRAG PostgreSQL Advisory Lock Design

## Overview

This document defines the next production-hardening step for the Java LightRAG PostgreSQL provider: database-level read/write coordination across multiple JVM processes.

`PostgresStorageProvider` already uses a fair in-process `ReentrantReadWriteLock`, which protects one JVM instance from torn reads during `restore(...)` and serializes top-level writes against `writeAtomically(...)`. That protection stops at the process boundary. Two application instances pointing at the same PostgreSQL schema can still interleave reads, writes, and restores in ways that violate the provider's intended consistency model.

The PostgreSQL provider should extend its current lock model with advisory locks so one database becomes the coordination boundary for all provider instances using the same schema and table prefix.

## Goals

- Serialize provider writes and restores across multiple JVM instances connected to the same PostgreSQL schema.
- Preserve the current local read/write lock semantics inside one JVM.
- Prevent top-level reads from observing torn state while another instance runs `restore(...)` or an atomic write.
- Keep the current storage SPI and provider construction model intact.

## Non-Goals

- Do not add an external lock service.
- Do not introduce a lock metadata table or schema migration for this phase.
- Do not redesign JDBC store implementations around shared connections for top-level operations.
- Do not add operational health endpoints in this phase.

## Approach Options

### Recommended: PostgreSQL Advisory Locks With Shared Reads And Exclusive Writes

Use PostgreSQL session-level advisory locks keyed by the configured schema and table prefix:

- shared advisory lock for top-level read operations
- exclusive advisory lock for top-level writes, `writeAtomically(...)`, and `restore(...)`

Benefits:

- no new tables or migration state
- naturally coordinates all provider instances using the same database
- matches the current read/write lock semantics closely
- low-risk extension of existing provider behavior

Trade-offs:

- each protected operation needs an extra lock session
- correctness depends on using a stable lock key derivation

### Alternative: Lock Table With `SELECT ... FOR UPDATE`

Create a provider-owned lock table and serialize through one row.

Benefits:

- uses familiar row-locking primitives
- lock ownership is visible in SQL inspection

Trade-offs:

- adds schema objects and migration overhead
- recovery and bootstrap must now preserve lock-table state
- more moving pieces than advisory locks for the same behavior

### Alternative: External Distributed Lock Service

Use Redis, ZooKeeper, or another external coordinator.

Benefits:

- could generalize beyond PostgreSQL

Trade-offs:

- too large an operational and packaging burden for an embedded Java SDK
- adds new infrastructure outside the current durable-provider scope

## Recommended Design

Adopt PostgreSQL advisory locks backed by a dedicated lock connection pool.

### Lock Semantics

The provider should preserve its current two-level model:

- JVM-local fair `ReentrantReadWriteLock`
- PostgreSQL advisory lock for cross-process coordination

Operations should map as follows:

- top-level read methods: local read lock + PostgreSQL shared advisory lock
- top-level write methods: local write lock + PostgreSQL exclusive advisory lock
- `writeAtomically(...)`: local write lock + PostgreSQL exclusive advisory lock
- `restore(...)`: local write lock + PostgreSQL exclusive advisory lock

This preserves the current visibility guarantees while extending them across provider instances.

### Lock Key Derivation

The advisory lock key should be deterministically derived from:

- schema name
- table prefix

This ensures:

- two providers sharing the same logical storage namespace coordinate with each other
- providers using different schemas or prefixes do not block each other

A stable 64-bit key derived from a SHA-256 digest of `<schema>:<prefix>` is sufficient for this phase.

### Dedicated Lock Pool

The advisory lock session must not share the same JDBC connection as the underlying JDBC store call, because top-level stores currently open their own connections internally.

The provider should therefore create:

- one primary Hikari pool for normal JDBC work
- one secondary Hikari pool dedicated to advisory-lock sessions

The lock pool prevents self-deadlock where a thread acquires a lock session and then blocks forever waiting for a second connection from the same exhausted pool to perform the actual store operation.

### Lock Manager Shape

Add a small backend-local helper such as:

- `PostgresAdvisoryLockManager`

Responsibilities:

- derive the stable lock key
- acquire and release shared locks
- acquire and release exclusive locks
- wrap a `Runnable` or supplier with the appropriate advisory lock lifecycle
- surface SQL failures as storage-level failures

This keeps lock SQL out of `PostgresStorageProvider`.

### Provider Integration

`PostgresStorageProvider` should:

- construct the lock manager during initialization
- close the lock pool when the provider closes
- wrap top-level facade operations with both local and advisory locks
- wrap `writeAtomically(...)` and `restore(...)` with the same exclusive advisory lock

The order should be consistent everywhere:

1. take the local JVM lock
2. take the advisory lock
3. run the delegate operation
4. release the advisory lock
5. release the local JVM lock

Using one order avoids lock inversion inside a single process.

### Failure Semantics

If advisory lock acquisition or release fails:

- fail the current operation
- preserve the original failure and suppress unlock failures where appropriate

For `writeAtomically(...)` and `restore(...)`, the existing JDBC transaction rollback rules remain unchanged. Advisory locking only gates entry; it does not change transaction ownership.

## Testing Strategy

Required coverage:

- two provider instances against the same schema serialize atomic writes
- a read on one provider blocks while another provider holds an atomic write
- existing same-process lock behavior continues to pass
- existing PostgreSQL provider tests continue to pass

Verification should include targeted `PostgresStorageProviderTest` coverage and full `./gradlew test`.
