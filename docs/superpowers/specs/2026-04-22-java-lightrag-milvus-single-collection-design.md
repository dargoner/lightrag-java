# Java LightRAG Milvus Single Collection Design

**Goal:** Collapse the current per-workspace, per-namespace Milvus layout into one shared physical collection while preserving the existing logical `chunks` / `entities` / `relations` retrieval semantics at the SDK API layer.

## Background

The current Milvus layout derives one collection name from:

- `collectionPrefix`
- `workspaceId`
- logical namespace (`chunks`, `entities`, `relations`)

For example, one relation-vector collection is currently created as:

- `fusion_rag_embedding_dev_aiplatform_kb_1284_relations`

This means one logical knowledge base currently fans out into three physical collections:

- `..._chunks`
- `..._entities`
- `..._relations`

That layout has three problems:

1. The number of Milvus collections grows linearly with workspace count.
2. Workspace isolation depends on collection naming instead of explicit row-level metadata.
3. `relations` already carries a schema superset compared with `chunks` and `entities`, so schema evolution must keep branching on collection type.

The confirmed product direction for this change is:

- use one global shared Milvus collection
- keep `workspace_id` as the only explicit tenant dimension
- do not introduce a separate `kb_id` field
- keep `kb` encoded inside `workspace_id`
- keep logical `chunks` / `entities` / `relations` behavior
- treat this as a clean-break storage change
- do not migrate old three-collection data

## Goals

1. Use exactly one physical Milvus collection for all workspaces and all vector record types.
2. Preserve the current Java-side `VectorStore` / `HybridVectorStore` API contract so query and indexing code can still address logical namespaces.
3. Make workspace isolation explicit through Milvus row metadata instead of collection naming.
4. Keep relation-only metadata (`src_id`, `tgt_id`, `file_path`) available in the unified schema.
5. Support clean rebuild of the shared collection without reading or migrating old workspace-specific collections.

## Non-Goals

1. Do not introduce a first-class `kb_id` concept into the Java SDK.
2. Do not preserve or read old three-collection Milvus data.
3. Do not redesign query strategies to remove logical namespace semantics.
4. Do not change relational or graph storage layout in this phase.
5. Do not add a long-lived dual-read or compatibility layer.

## Options Considered

### Option 1: One shared collection, keep logical namespace semantics

Use one physical collection and add row metadata:

- `workspace_id`
- `record_type`

The existing logical namespace arguments remain in the Java API, but Milvus internally translates them into filters on `record_type`.

Pros:

- smallest blast radius above the Milvus adapter layer
- indexing, query, snapshot, and restore flows keep their current mental model
- matches the requested product direction exactly

Cons:

- Milvus adapter logic becomes more complex
- the unified schema must include relation-only fields for every row

### Option 2: One shared collection and remove namespace semantics end-to-end

Rewrite upper layers to stop talking about `chunks` / `entities` / `relations` as logical namespaces.

Pros:

- cleaner pure data model
- fewer storage-specific concepts leaking upward over time

Cons:

- much larger API and query-layer rewrite
- not necessary for the requested storage consolidation

### Option 3: Three global collections, row-filter by workspace only

Reduce collection count but still keep separate physical collections for `chunks`, `entities`, and `relations`.

Pros:

- simpler than full unification
- relation-specific schema stays isolated

Cons:

- does not satisfy the one-collection target

## Recommended Design

Adopt **Option 1**.

The Java SDK will continue to expose logical namespaces:

- `chunks`
- `entities`
- `relations`

But Milvus will persist all records into a single shared collection and apply two mandatory filters on every read path:

- `workspace_id == current workspace`
- `record_type == requested namespace`

This keeps the storage contract simple for callers while moving the Milvus physical layout to a single collection.

## Core Design Decisions

### 1. Split technical primary key from business vector identity

The current Milvus schema uses a single `id` field as the primary key. That only works because different workspaces and namespaces are isolated by separate collections.

With one shared collection, using business `id` directly would cause collisions across:

- different workspaces
- different record types
- identical chunk/entity/relation IDs in different logical scopes

The new schema therefore separates:

- `pk_id`
  Technical Milvus primary key. Deterministic hash derived from:
  `workspace_id + "\u001F" + record_type + "\u001F" + vector_id`
- `vector_id`
  Business ID currently passed through the Java vector store APIs and returned in search results

This preserves existing Java semantics while giving Milvus a globally unique primary key.

### 2. Make workspace isolation explicit in row metadata

Each Milvus row will carry:

- `workspace_id`

The Java SDK will not introduce a separate `kb_id` field. If a knowledge base identity is encoded today as part of `workspace_id` (for example `dev_aiplatform_kb_1284`), that continues unchanged.

All list/search/delete operations must scope to the current `workspace_id`.

### 3. Preserve namespace semantics through `record_type`

Each row will carry:

- `record_type`

Allowed values:

- `chunks`
- `entities`
- `relations`

The current Java methods continue to accept `namespace`, but internally the Milvus adapter interprets it as `record_type`.

### 4. Use one fixed shared collection name

The current `MilvusVectorConfig.collectionName(workspaceId, namespace)` naming model will no longer be used for Milvus physical isolation.

Instead, the adapter will resolve one shared collection name from configuration. To minimize config churn:

- derive the shared collection name from `collectionPrefix`
- strip one trailing underscore if present
- if the normalized name is blank, fall back to `lightrag_vectors`

With the current example prefix:

- `fusion_rag_embedding_`

the shared collection name becomes:

- `fusion_rag_embedding`

This preserves operator intent while removing workspace and namespace suffixes from the physical layout.

### 5. Use one schema superset for all row types

The unified collection schema will contain the full superset needed by all three logical record types.

Rows for `chunks` and `entities` will leave relation-only fields blank.

Rows for `relations` will continue populating:

- `src_id`
- `tgt_id`
- `file_path`

## Unified Milvus Schema

The shared collection schema will be:

- `pk_id VARCHAR(64)` primary key
- `vector_id VARCHAR(65535)`
- `workspace_id VARCHAR(65535)`
- `record_type VARCHAR(32)`
- `dense_vector FLOAT_VECTOR(<dims>)`
- `searchable_text VARCHAR(65535)`
- `full_text VARCHAR(65535)`
- `sparse_vector SPARSE_FLOAT_VECTOR`
- `src_id VARCHAR(512)`
- `tgt_id VARCHAR(512)`
- `file_path VARCHAR(32768)`

### Field semantics

- `pk_id`
  Deterministic technical key for upsert stability.
- `vector_id`
  Existing logical vector ID returned to the Java caller.
- `workspace_id`
  Current `WorkspaceScope.workspaceId()`.
- `record_type`
  One of `chunks`, `entities`, `relations`.
- `src_id`, `tgt_id`, `file_path`
  Populated only for relation rows; empty strings for other row types.

### Indexing and analyzer

Keep the current indexing strategy:

- dense auto index on `dense_vector`
- BM25 sparse index on `sparse_vector`
- analyzer-enabled `full_text`

No separate scalar index is required at the SDK design level for `workspace_id` or `record_type`; they are mandatory filter fields. If Milvus version-specific scalar indexing proves necessary later, that can be an implementation detail without changing the Java contract.

`vector_id` and `workspace_id` deliberately use the same large varchar budget as other unrestricted text fields in Milvus-facing schema. This avoids introducing a new hidden SDK-level max length for existing IDs that are currently validated only as non-blank strings.

## Read/Write Behavior

### Writes

`saveAll(namespace, vectors)` and `saveAllEnriched(namespace, records)` keep their signatures.

For every record, the adapter will:

1. resolve `workspace_id` from the `MilvusVectorStore` instance
2. resolve `record_type` from the `namespace` argument
3. compute `pk_id` from `workspace_id + record_type + vector_id`
4. upsert into the shared collection

### Searches

`search(namespace, ...)` keeps its signatures.

Every Milvus search request must apply a filter equivalent to:

```text
workspace_id == "<current-workspace>" && record_type == "<namespace>"
```

This applies to:

- semantic search
- keyword search
- hybrid search

Search responses must return `vector_id` as the Java-visible ID, not `pk_id`.

### List

`list(namespace)` must query the shared collection with the same mandatory filter and return sorted `VectorRecord(vector_id, dense_vector)`.

### Delete

`deleteNamespace(namespace)` will no longer drop or clear one Milvus collection.

Instead, it deletes rows matching:

```text
workspace_id == "<current-workspace>" && record_type == "<namespace>"
```

### Flush

`flushNamespaces(namespaces)` becomes a no-op per-namespace translation layer that resolves all requested logical namespaces down to the same shared physical collection and flushes it once.

## Snapshot and Restore Semantics

Upper layers will continue to think in logical namespaces:

- `chunks`
- `entities`
- `relations`

That means:

- `StorageSnapshots.capture(...)` remains namespace-based
- `VectorStorageAdapter.VectorSnapshot` remains namespace-based
- `MilvusVectorStorageAdapter.restore(...)` remains namespace-based

Only the Milvus projection implementation changes:

- namespace capture becomes filtered reads from the shared collection
- namespace restore becomes filtered delete + shared-collection upsert

This keeps the blast radius constrained to the Milvus adapter boundary.

## API Compatibility Strategy

### Keep public Java interfaces unchanged

Do not change:

- `VectorStore`
- `HybridVectorStore`
- `VectorStorageAdapter`

The `namespace` string argument remains part of the Java contract.

### Internal Milvus adapter evolution

The main implementation changes are internal:

- `MilvusVectorConfig`
- `MilvusVectorStore`
- `MilvusClientAdapter`
- `MilvusSdkClientAdapter`
- `MilvusVectorStorageAdapter`

The adapter layer becomes responsible for translating:

- logical namespace -> `record_type`
- workspace-scoped store instance -> `workspace_id`
- business vector ID -> technical `pk_id`

## Schema Drift and Upgrade Strategy

This is a clean-break Milvus storage change.

### Confirmed behavior

- old workspace-specific collections are not read
- old workspace-specific collections are not migrated
- old workspace-specific collections may be discarded

### Shared collection bootstrap

On startup, the SDK ensures only the new shared collection schema.

If the target shared collection exists but is incompatible with the new schema:

- `STRICT_REBUILD` drops and recreates the shared collection
- `STRICT_FAIL` fails fast
- `IGNORE` leaves it untouched and does not promise correct behavior if the schema is actually incompatible

Because the new layout uses one global shared collection, `STRICT_REBUILD` is a global operation:

- it clears Milvus data for every workspace stored in that shared collection
- it is not scoped to the current workspace

### Legacy collection cleanup

No migration logic is added.

Optional best-effort cleanup may drop the three legacy collections for the current workspace:

- `<prefix><workspace>_chunks`
- `<prefix><workspace>_entities`
- `<prefix><workspace>_relations`

But the SDK does not attempt to enumerate and clean every historical workspace automatically. Operators should treat this as a storage reset and can delete old collections out-of-band if desired.

## Data Flow Changes By Layer

### `MilvusVectorConfig`

- add a new shared-collection name resolver
- stop deriving physical collection names from workspace and namespace

### `MilvusVectorStore`

- always target one shared collection
- keep namespace arguments only as logical type selectors
- attach workspace metadata to every row

### `MilvusClientAdapter`

Expand request objects so read paths can carry:

- collection name
- filter expression
- returned output fields

The current collection-only search requests are no longer sufficient for shared-collection isolation.

### `MilvusSdkClientAdapter`

- create the unified schema
- upsert `pk_id`, `vector_id`, `workspace_id`, `record_type`, and payload fields
- apply row filters to search/list/delete
- return `vector_id` to Java callers instead of `pk_id`

### `MilvusVectorStorageAdapter`

No semantic change to its public behavior. It still restores and captures three logical namespaces, but the Milvus projection implementation maps them into one physical collection.

## Testing Strategy

The implementation phase must cover:

### Unit tests

1. shared collection name normalization from `collectionPrefix`
2. unified schema field set
3. `pk_id` derivation stability
4. search/list/delete filter generation
5. search results returning `vector_id` instead of `pk_id`
6. relation and non-relation row serialization into the unified payload shape

### Integration tests

1. chunk/entity/relation records for one workspace coexist in one collection
2. same `vector_id` across two workspaces does not collide
3. same `vector_id` across two record types in one workspace does not collide
4. filtered search only returns rows from the requested workspace and record type
5. restore clears only the targeted workspace + record type slice
6. relation metadata fields survive save/load/search flows

### End-to-end regression tests

1. `NAIVE` query still retrieves chunk vectors correctly
2. local query still retrieves entity vectors correctly
3. global query still retrieves relation vectors correctly
4. mixed providers (`PostgresMilvusNeo4jStorageProvider`, `MySqlMilvusNeo4jStorageProvider`) continue to restore and rebuild vectors correctly

## Risks

### 1. Hidden ID collision assumptions

Some current code may assume Milvus primary key equals business vector ID. The new design breaks that intentionally. Any code path that reads or logs the Milvus primary key directly must be audited.

### 2. Filter support differences across Milvus APIs

The current adapter code assumes collection-level isolation and does not carry scalar filters through all search request types. The implementation must verify equivalent filter support across semantic, keyword, hybrid, query, and delete paths.

### 3. Shared collection rebuild blast radius

With one shared collection, `STRICT_REBUILD` impacts all workspaces stored there, not just one workspace. This is acceptable for the requested clean-break phase, but it must be called out clearly in code comments and release notes.

## Success Criteria

This design is complete when:

1. one Milvus collection physically stores chunk, entity, and relation vectors for all workspaces
2. all Java query/indexing code still uses logical `chunks` / `entities` / `relations` namespaces
3. workspace isolation is enforced by row filters, not collection naming
4. search results still return business vector IDs expected by current Java callers
5. no legacy three-collection Milvus migration path is required
