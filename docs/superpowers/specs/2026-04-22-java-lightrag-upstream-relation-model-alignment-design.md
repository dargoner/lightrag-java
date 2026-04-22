# Java LightRAG Upstream Relation Model Alignment Design

**Goal:** Rebuild the Java relation model so relation semantics, storage schema, graph shape, and vector payloads align with upstream LightRAG instead of preserving the current Java-specific `relationType` identity model.

## Scope

- Redefine relation semantics around upstream-style `src_id`, `tgt_id`, `keywords`, `description`, `weight`, `source_id`, and `file_path`.
- Align PostgreSQL, MySQL, Neo4j, and Milvus relation storage formats, field names, and length rules with upstream LightRAG as closely as practical.
- Replace the current public Java relation-management API surface that depends on `relationType` and `currentRelationType`.
- Define a clean-break upgrade path from the existing Java relation model to the new upstream-aligned model without preserving in-place relation data.
- Ship the change as the next minor version line.

## Non-Goals

- Preserve source or binary compatibility for the current relation-management API.
- Keep the current `sourceEntityId + normalizedRelationType + targetEntityId` identity semantics.
- Introduce a staged long-lived compatibility layer for both old and new relation models.
- Redesign unrelated query strategies, entity semantics, or task APIs beyond what relation-model alignment requires.

## Current Problem

The current Java SDK diverges from upstream in three coupled ways:

1. Relation semantics are centered on `relationType`, while upstream centers relation meaning on `keywords`.
2. Relation identity uses `sourceEntityId + normalizedRelationType + targetEntityId`, while upstream treats graph edges as undirected endpoint pairs and merges relation meaning into the stored edge payload.
3. The same readable relation ID is reused across graph storage and vector storage, which creates length pressure in Milvus and ties business identity to storage technical constraints.

This divergence leaks into:

- public APIs: `CreateRelationRequest.relationType`, `EditRelationRequest.currentRelationType`, `newRelationType`
- relation merge and delete behavior
- PostgreSQL and Neo4j schema semantics
- Milvus vector primary-key design
- README and demo examples

## Upstream Semantics To Align

Observed upstream LightRAG behavior:

- relations are treated as undirected unless explicitly modeled otherwise
- the graph edge is unique by endpoint pair, not by relation keyword text
- `keywords` describe the relationship and are merged across repeated inserts
- `description` is merged and may be summarized
- `source_id` tracks supporting chunk IDs using `GRAPH_FIELD_SEP`
- vector IDs use short hash-based technical IDs instead of readable relation strings
- Milvus relation metadata keeps `src_id` and `tgt_id` as explicit scalar fields

Relevant upstream references:

- undirected relation rule in prompt and graph abstractions
- `make_relation_chunk_key(src, tgt)` sorts endpoints before generating the relation key
- relation vector IDs are generated with `compute_mdhash_id(..., prefix="rel-")`
- Milvus relation collection uses `id VARCHAR(64)` and `src_id`/`tgt_id VARCHAR(512)`

## Core Design Decisions

### 1. Replace `relationType` with upstream `keywords`

The Java relation model will no longer treat `relationType` as the primary semantic field.

The new core relation payload is:

- `relation_id`
- `src_id`
- `tgt_id`
- `keywords`
- `description`
- `weight`
- `source_id`
- `file_path`

`keywords` is a comma-separated normalized string that carries relation meaning. It does not participate in uniqueness.

### 2. Make relations unique by endpoint pair

Relations are undirected and canonicalized before storage:

- trim both endpoints
- normalize case and ordering for identity purposes
- sort endpoints into canonical order
- reject self-loops where canonical `src_id == tgt_id`

Business uniqueness is therefore:

- one relation per workspace + canonical endpoint pair

This matches upstream edge semantics and removes the current Java requirement to provide `relationType` just to locate a relation.

### 3. Split business identity from technical IDs

The system will use two separate concepts:

- `relation_pair_key`
  Canonical endpoint-pair identity used for deduplication and migration logic.
- `relation_id`
  Short technical storage ID derived from the canonical endpoint pair.

`relation_id` will use upstream-style hashed IDs:

- format: `rel-` + `md5(canonical_src + canonical_tgt)`
- effective length: 36 characters
- schema budget: 64 characters

This keeps technical IDs stable, short, and independent from the length of readable entity names.

### 4. Use upstream field naming across storage

Core storage-facing names will move to snake_case everywhere relation data is persisted:

- relational tables
- Neo4j relation properties
- Milvus payload fields
- migration and snapshot representations

Camel-case names may still appear in thin Java controller/request DTOs if needed, but the internal relation domain model and storage contracts should use upstream-aligned names directly.

## Unified Length and Type Rules

### Business constraints

- `src_id`: max 256 characters
- `tgt_id`: max 256 characters
- `keywords`: text, no small fixed limit
- `description`: text, no small fixed limit
- `source_id`: text, may contain multiple chunk IDs joined by `<SEP>`
- `file_path`: text, may contain multiple file paths joined by `<SEP>`
- `weight`: finite double

The 256 endpoint limit aligns Java relation endpoints with upstream entity identifier truncation behavior.

### Technical constraints

- `relation_id`: `VARCHAR(64)` or equivalent
- Milvus relation collection `id`: `VARCHAR(64)`
- Milvus relation collection `src_id`: `VARCHAR(512)`
- Milvus relation collection `tgt_id`: `VARCHAR(512)`
- Milvus relation collection `file_path`: `VARCHAR(32768)`

## Storage Design

### PostgreSQL

Replace the current relation schema with:

- `workspace_id TEXT NOT NULL`
- `relation_id VARCHAR(64) NOT NULL`
- `src_id TEXT NOT NULL`
- `tgt_id TEXT NOT NULL`
- `keywords TEXT NOT NULL`
- `description TEXT NOT NULL`
- `weight DOUBLE PRECISION NOT NULL`
- `source_id TEXT NOT NULL`
- `file_path TEXT NOT NULL`

Constraints:

- `PRIMARY KEY (workspace_id, relation_id)`
- `UNIQUE (workspace_id, src_id, tgt_id)`

Remove the old Java-specific `type` column and any logic that depends on endpoint-plus-type identity.

### MySQL

Use the same semantic fields, with MySQL-appropriate technical types:

- `workspace_id VARCHAR(191) NOT NULL`
- `relation_id VARCHAR(64) NOT NULL`
- `src_id TEXT NOT NULL`
- `tgt_id TEXT NOT NULL`
- `keywords TEXT NOT NULL`
- `description LONGTEXT NOT NULL`
- `weight DOUBLE NOT NULL`
- `source_id LONGTEXT NOT NULL`
- `file_path LONGTEXT NOT NULL`

Constraints:

- `PRIMARY KEY (workspace_id, relation_id)`

Because MySQL handles long indexed text less gracefully, the hashed `relation_id` is the only required key. Canonical endpoint uniqueness is enforced by application logic during writes and migration.

### Neo4j

Store exactly one canonical relation per endpoint pair with relation properties:

- `relation_id`
- `src_id`
- `tgt_id`
- `keywords`
- `description`
- `weight`
- `source_id`
- `file_path`
- `workspace_id`
- `scoped_id`

Constraint model:

- entity uniqueness remains workspace-scoped
- relation uniqueness becomes workspace + `scoped_id`
- `scoped_id` is derived from `relation_id`, not from the old readable relation string

Query behavior stays logically undirected even if one canonical direction is stored.

### Milvus

Rebuild relation-vector records around upstream structure:

- `id VARCHAR(64)` primary key
- `dense_vector`
- searchable/full-text payload
- `src_id VARCHAR(512)`
- `tgt_id VARCHAR(512)`
- `file_path VARCHAR(32768)`

`keywords` is not modeled as a separate scalar field. It contributes to the relation text that is embedded and full-text indexed:

```text
keywords \t src_id \n tgt_id \n description
```

This mirrors upstream relation-vector content construction and keeps relation metadata responsibilities clearly separated:

- ID for lookup
- endpoints for filtering
- text payload for retrieval quality

## API Redesign

### Public API changes

Remove the current request shapes and semantics:

- the current `CreateRelationRequest` payload contract
- `EditRelationRequest`
- all request fields named `relationType`, `currentRelationType`, `newRelationType`

Introduce upstream-aligned request/response models:

- `CreateRelationRequest`
  - `sourceEntityName`
  - `targetEntityName`
  - `keywords`
  - `description`
  - `weight`
  - optional `sourceId`
  - optional `filePath`
- `UpdateRelationRequest`
  - `sourceEntityName`
  - `targetEntityName`
  - optional `keywords`
  - optional `description`
  - optional `weight`
  - optional `sourceId`
  - optional `filePath`
- `DeleteRelationRequest`
  - `sourceEntityName`
  - `targetEntityName`
- `GraphRelation`
  - `relationId`
  - `srcId`
  - `tgtId`
  - `keywords`
  - `description`
  - `weight`
  - `sourceId`
  - `filePath`

Java method names may remain in existing `LightRag` style, but the payload semantics change to the upstream relation model.

### Delete and update semantics

- updates locate the relation by canonical endpoint pair only
- deletes locate the relation by canonical endpoint pair only
- no request may require `relationType` just to identify a relation

### Merge semantics

Entity merge must rewrite relation endpoints into canonical order and then fold duplicate endpoint pairs by merging:

- `keywords`
- `description`
- `weight`
- `source_id`
- `file_path`

Self-loops created by entity merge are dropped.

## Upgrade Strategy

This redesign is a clean-break schema and API change. The SDK will not provide an in-place relation migration path from the old Java model.

### Upgrade contract

Upgrading to the new relation model means:

- old persisted relation rows are treated as incompatible
- old Neo4j relation edges are treated as incompatible
- old Milvus relation vector collections are treated as incompatible
- relation data must be rebuilt from source documents or re-imported through the new API

### Required operator actions

Before enabling the new version, operators must:

1. take a full backup if historical relation data matters
2. drop or isolate old relation storage structures
3. recreate relation tables, graph edges, and relation-vector collections with the new schema
4. re-run document ingestion or import custom graph data through the new upstream-aligned API

### Supported cutover shape

Supported cutover:

- deploy new code
- recreate relation storage
- rebuild relation data

Unsupported cutover:

- mixed old/new relation schemas in the same runtime
- automatic row-by-row transformation of old relation data
- long-lived compatibility layers for old relation identifiers

### Why no migration

The semantic break is too large for a safe in-place migration:

- relation identity changes from `src + type + tgt` to canonical endpoint pair only
- multiple old Java relations can collapse into one upstream-aligned relation
- vector IDs change from readable relation strings to short hash IDs
- graph, relational, and vector backends all change shape together

The safe and explicit model is therefore: break compatibility, rebuild relation data, and validate the rebuilt state.

## Query and Retrieval Effects

This redesign changes retrieval inputs and graph-loading behavior in several ways:

- relation retrieval no longer distinguishes multiple relation rows by `type`
- multiple old Java relation rows between the same endpoints become one merged relation
- query strategies that currently expose `relationType` must move to `keywords`
- path and evidence objects must report the new relation payload shape

This is an intentional semantic change, not a compatibility bug.

Existing persisted relation data from the old Java model is not expected to survive the upgrade in place.

## Testing Requirements

Add or rewrite coverage for:

- canonical endpoint ordering
- self-loop rejection
- duplicate endpoint-pair merge during ingest
- migration from multiple `type`-based relations into one `keywords`-based relation
- PostgreSQL relation schema bootstrap and migration
- MySQL relation schema bootstrap and migration
- Neo4j uniqueness and rewrite behavior
- Milvus relation collection recreation and payload shape
- query/path outputs returning `keywords` instead of `relationType`
- delete/update by endpoint pair only

End-to-end tests must verify that:

- `A-B` and `B-A` resolve to the same relation
- old `works_with` + `reports_to` rows become one `keywords` payload
- relation vectors are regenerated with short IDs
- graph store, relation store, and vector store agree on the same `relation_id`

## Documentation Changes

Update the following documentation and examples:

- root `README.md`
- `README_zh.md` if present
- demo controller examples
- graph-management docs under `docs/superpowers/specs`

All examples must stop using `relationType` and show the new relation payload shape.

## Versioning

This redesign is an intentional public API and storage-model break. It must ship as the next minor version line.

Current repository default version:

- `projectVersion=0.16.0-SNAPSHOT`

Required version change:

- implementation branch and merged `main` should move to `0.17.0-SNAPSHOT`
- the first formal release containing this redesign should be `0.17.0`

This is a required part of the work, not an optional release follow-up.

## Risks

- existing relation data must be rebuilt, which increases upgrade cost
- existing consumers compiled against current graph-management APIs will break
- all relation embeddings must be regenerated, which can be expensive on large datasets

## Mitigations

- document the upgrade as a clean break in README and release notes
- require pre-upgrade backup when historical relation data matters
- fail fast when the SDK detects old incompatible relation schema artifacts
- provide deterministic rebuild verification across relational, graph, and vector stores

## Acceptance Criteria

- no persisted relation schema depends on `relationType`
- all persisted relation data uses upstream-aligned field semantics
- all relation uniqueness is based on canonical endpoint pairs
- all vector relation IDs are short hash IDs
- all relation-management APIs use the new semantics
- all relation stores and tests pass with the rebuilt schema
- repository version is advanced from `0.16.x` to `0.17.0-SNAPSHOT`
