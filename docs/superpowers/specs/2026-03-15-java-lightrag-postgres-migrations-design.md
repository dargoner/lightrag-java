# Java LightRAG PostgreSQL Schema Migrations Design

## Overview

This document defines the next production-hardening step for the Java LightRAG PostgreSQL provider: schema version tracking and SDK-managed migrations.

`PostgresSchemaManager` currently bootstraps the full schema with idempotent SQL, but it does not record schema version state. That leaves two operational gaps:

- the SDK cannot distinguish a fresh database from an older unversioned deployment
- the SDK cannot fail fast when it encounters a schema created by a newer release

The provider should add a lightweight migration mechanism inside the existing bootstrap path without introducing an external migration framework.

## Goals

- Track PostgreSQL schema version inside the target schema.
- Preserve current first-use bootstrap behavior for empty databases.
- Baseline already-bootstrapped unversioned databases without forcing a manual migration step.
- Fail fast when the database schema version is newer than the SDK supports.
- Keep schema initialization transactional so failed migrations do not leave partial state behind.

## Non-Goals

- Do not introduce Flyway, Liquibase, or another external migration framework.
- Do not redesign `PostgresStorageProvider` or the storage SPI.
- Do not add cross-process locking in this phase.
- Do not change query semantics or storage layout beyond required migration metadata.

## Approach Options

### Recommended: SDK-Managed Version Table With Ordered Migrations

Add a small metadata table, keep migrations in code, and have `PostgresSchemaManager` reconcile the current schema version on startup.

Benefits:

- minimal operational footprint
- keeps bootstrap and migrations in one place
- supports future versioned upgrades without changing provider APIs
- allows automatic baseline of existing unversioned databases

Trade-offs:

- migration logic remains application-owned
- complex future schema changes will still need careful transactional design

### Alternative: Keep Idempotent Bootstrap Only

Continue relying on `CREATE TABLE IF NOT EXISTS` and validation checks.

Benefits:

- smallest code change

Trade-offs:

- no durable schema version tracking
- cannot distinguish legacy schema from unsupported future schema
- no structured migration path

### Alternative: Adopt Flyway/Liquibase Now

Move schema management to a dedicated migration framework.

Benefits:

- mature operational tooling
- easier out-of-process migration workflows

Trade-offs:

- introduces a large dependency and configuration surface
- over-scoped for the current provider maturity
- complicates the lightweight embedded SDK story

## Recommended Design

Adopt an SDK-managed version table with ordered in-code migrations.

### Migration Metadata

Create one metadata table inside the configured schema:

- table name: `<prefix>schema_version`
- columns:
  - `schema_key TEXT PRIMARY KEY`
  - `version INTEGER NOT NULL`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`

The table stores one logical row for the PostgreSQL storage schema, keyed by `storage`.

### Version Model

Define one current schema version:

- `v1`: current `documents`, `chunks`, `entities`, `entity_aliases`, `entity_chunks`, `relations`, `relation_chunks`, and `vectors` tables, plus the `vector` extension bootstrap

`PostgresSchemaManager` should keep migrations as an ordered list. Version `1` is implemented as the existing bootstrap SQL plus vector-dimension validation.

### Startup Flow

On `bootstrap()`:

1. open one transaction
2. ensure the target schema exists
3. ensure the schema-version table exists
4. read the stored schema version row
5. if no version row exists, run migrations from `v1`
6. if version row exists and is greater than the supported version, fail fast
7. if version row exists and is less than the supported version, run each missing migration in order
8. validate vector dimensions against the configured value
9. commit

All steps stay inside one transaction so version metadata and schema objects move together.

### Legacy Upgrade Rules

An unversioned schema should be upgraded by replaying the idempotent `v1` migration statements and then recording version `1`.

This preserves the existing bootstrap behavior for legacy deployments that have only part of the current table set, such as earlier layouts that predate graph or vector tables. Explicit structural validation remains limited to vector-dimension checks in this phase; broader table-shape drift detection is deferred.

### Failure Semantics

Migration failures must roll back:

- created application tables
- created metadata table rows
- any partially applied migration statements

This preserves the current bootstrap atomicity guarantees.

### Compatibility Rules

- empty database: auto-bootstrap to latest supported version
- legacy unversioned database: validate and baseline to `v1`
- supported versioned database: no-op or upgrade forward
- newer unsupported version: throw an `IllegalStateException` with the found and supported versions

## Implementation Notes

Recommended additions inside `PostgresSchemaManager`:

- a migration descriptor record or class containing `version` and SQL statements
- helpers to:
  - create the metadata table
  - read and write schema-version rows
  - apply migrations in order
  - record schema version after successful legacy upgrade

The existing bootstrap SQL, including `CREATE EXTENSION IF NOT EXISTS vector`, should become the `v1` migration body instead of a special-case code path. If the database user cannot create the extension, bootstrap should fail with the underlying SQL error wrapped as a schema-bootstrap failure.

## Testing Strategy

Required coverage:

- fresh database creates application tables and schema-version metadata
- existing unversioned schema is baselined to `v1`
- existing partial legacy schemas are upgraded to `v1` and recorded
- unsupported newer schema version fails fast
- migration failure rolls back both schema objects and version metadata
- existing vector-dimension drift detection still fails

Verification should include targeted PostgreSQL integration tests and full `./gradlew test`.
