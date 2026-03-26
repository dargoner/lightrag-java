# Java LightRAG Multi-Workspace Shared Neo4j Design

## Overview

This document defines the next storage and API evolution step for `lightrag-java`: support one `LightRag` instance serving multiple knowledge-base workspaces while keeping PostgreSQL physically isolated per workspace and moving Neo4j to a shared-database multi-workspace model.

The current SDK and Spring starter assume a single storage view per `LightRag` instance. PostgreSQL workspace isolation already exists through per-workspace table prefixes, but `postgres-neo4j` workspace isolation is blocked because the Neo4j graph store is single-database and single-workspace in behavior. It does not carry `workspaceId` through graph reads, writes, snapshot capture, or restore.

The next phase should make `workspaceId` an explicit SDK input on every ingest, query, and graph-management call, then use that input to resolve a workspace-scoped storage view for the duration of the call.

## Goals

- Allow one `LightRag` instance to serve multiple workspaces.
- Require callers to pass `workspaceId` explicitly on ingest, query, graph, delete, and document-status APIs.
- Keep PostgreSQL isolation as it exists today: one workspace maps to one derived table-prefix set.
- Move Neo4j to one shared database that stores multiple workspaces safely through `workspaceId` partitioning.
- Guarantee that Neo4j snapshot capture, restore, and compensation affect only the current workspace.
- Preserve the current high-level provider contract: the execution path still works through a workspace-scoped `AtomicStorageProvider`.
- Leave a clean seam for a future external vector backend such as Milvus.

## Non-Goals

- Do not redesign PostgreSQL into shared tables with a `workspaceId` column in this phase.
- Do not introduce hidden request context, `ThreadLocal`, or implicit workspace propagation.
- Do not attempt XA or two-phase commit across PostgreSQL, Neo4j, and future vector systems.
- Do not make this phase backward-compatible at the public `LightRag` API level; this is a deliberate breaking change.
- Do not implement a Milvus backend in this phase.

## Architectural Options

### Recommended: Explicit Workspace API With Workspace-Scoped Storage Resolution

Expose `workspaceId` on every public runtime operation, resolve a workspace-scoped provider at the start of each call, then run all internal logic against that scoped provider.

Benefits:

- matches the required calling model exactly
- avoids implicit state and async propagation hazards
- keeps storage-specific workspace mapping inside provider assembly
- lets PostgreSQL and Neo4j use different physical isolation strategies behind one runtime model
- creates the cleanest future seam for alternate vector backends

Trade-offs:

- this is a breaking public API change
- all pipelines and query paths must stop assuming one fixed provider per `LightRag`

### Alternative: Bind Workspace To `LightRag` Instances

Create one `LightRag` instance per workspace and keep the current storage model internally.

Benefits:

- smaller core API change
- simpler internal execution model

Trade-offs:

- does not satisfy the required “one instance serves many workspaces” runtime model
- pushes workspace orchestration to callers instead of the SDK

### Alternative: Explicit Workspace Parameters On Every Store SPI

Add `workspaceId` to `GraphStore`, `VectorStore`, `DocumentStore`, and related SPI methods directly.

Benefits:

- makes workspace propagation explicit everywhere

Trade-offs:

- overexposes storage concerns to every caller and internal component
- clashes with PostgreSQL’s existing per-provider table-prefix isolation model
- makes future storage swaps heavier than necessary

## Recommendation

Adopt the recommended option.

The new core runtime model should be:

- one `LightRag` instance
- explicit `workspaceId` on every externally callable data operation
- one internal `WorkspaceScope`
- one resolved workspace-scoped `AtomicStorageProvider` per call

## Recommended Design

### Public API Shape

`LightRag` should become a multi-workspace runtime. Public methods should accept `workspaceId` explicitly:

- `ingest(String workspaceId, List<Document> documents)`
- `ingestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options)`
- `query(String workspaceId, QueryRequest request)`
- `createEntity(String workspaceId, CreateEntityRequest request)`
- `createRelation(String workspaceId, CreateRelationRequest request)`
- `editEntity(String workspaceId, EditEntityRequest request)`
- `editRelation(String workspaceId, EditRelationRequest request)`
- `mergeEntities(String workspaceId, MergeEntitiesRequest request)`
- `deleteByEntity(String workspaceId, String entityName)`
- `deleteByRelation(String workspaceId, String sourceEntityName, String targetEntityName)`
- `deleteByDocumentId(String workspaceId, String documentId)`
- `getDocumentStatus(String workspaceId, String documentId)`
- `listDocumentStatuses(String workspaceId)`
- `saveSnapshot(String workspaceId, Path path)`
- `restoreSnapshot(String workspaceId, Path path)`

This is a deliberate breaking change. The old zero-workspace methods should not remain as silent aliases because that would reintroduce ambiguity into a design that now depends on explicit workspace routing.

Add a small validated value object:

```java
public record WorkspaceScope(String workspaceId) {
    public WorkspaceScope {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        workspaceId = workspaceId.strip();
    }
}
```

Public methods accept raw `String workspaceId`, validate it once, and convert it into `WorkspaceScope` before doing any storage work.

### Storage Resolution Layer

Add a new assembly seam:

```java
public interface WorkspaceStorageProvider extends AutoCloseable {
    AtomicStorageProvider forWorkspace(WorkspaceScope scope);
}
```

This interface owns workspace-to-storage mapping. It should decide:

- which PostgreSQL table prefix belongs to the workspace
- which graph-store view belongs to the workspace
- which vector-store implementation belongs to the workspace
- which snapshot naming strategy belongs to the workspace

Lifecycle contract:

- `forWorkspace(...)` returns a cached, reusable, long-lived workspace-scoped provider for that workspace
- repeated calls for the same workspace return the same logical provider instance
- callers must not close the returned provider directly
- `WorkspaceStorageProvider` owns the workspace-provider cache and closes all cached providers from its own `close()` method
- `LightRag` uses the returned provider during a call but does not dispose it after the call

`LightRagBuilder` should gain a new entry point:

- `workspaceStorage(WorkspaceStorageProvider workspaceStorageProvider)`

The builder should allow either:

- legacy `storage(StorageProvider)` for single-workspace use cases
- new `workspaceStorage(...)` for multi-workspace runtime use cases

but not both at the same time.

Internally, `LightRag` should resolve a workspace-scoped `AtomicStorageProvider` at the beginning of each call and then pass that scoped provider into indexing, graph-management, deletion, and query execution.

Pipelines should stop holding one fixed provider instance for the lifetime of the `LightRag` object. They should instead keep algorithm/configuration state and operate on a provider supplied at execution time.

### PostgreSQL Strategy

PostgreSQL stays on the current isolation model:

- one shared schema
- one workspace-specific derived table prefix

This phase should not redesign PostgreSQL into shared multi-tenant tables. The workspace-scoped provider should continue building a `PostgresStorageConfig` per workspace using the existing table-prefix derivation logic already used by the Spring starter.

That keeps PostgreSQL concerns stable while the SDK evolves around multi-workspace runtime behavior.

### Neo4j Shared-Database Strategy

Neo4j should move to one shared database that stores multiple workspaces logically.

Recommended graph model:

- node label: `:Entity`
- relationship type: `:RELATION`
- all persisted graph records carry `workspaceId`

Node shape:

- `(:Entity {workspaceId, id, name, type, description, aliases, sourceChunkIds, materialized})`

Relationship shape:

- `(:Entity)-[:RELATION {workspaceId, id, type, description, weight, sourceChunkIds}]->(:Entity)`

Rules:

- entity identity is unique within one workspace, not globally
- relation identity is unique within one workspace, not globally
- placeholder endpoint nodes created during relation writes must carry the same `workspaceId`
- every graph read query must include `workspaceId`
- every graph write query must include `workspaceId`

The existing Neo4j store should be replaced or refactored into a workspace-scoped variant that is constructed with:

- shared Neo4j connectivity
- fixed database name
- `WorkspaceScope`

The workspace-scoped graph store should behave like a normal `GraphStore`, but it must never see records outside its own workspace.

### Neo4j Constraints And Identity

The SDK should keep exposing raw entity and relation IDs to callers without forcing workspace information into those IDs.

Internally, Neo4j uniqueness must be scoped. The intended semantic uniqueness is:

- entity key: `(workspaceId, id)`
- relation key: `(workspaceId, id)`

If direct composite uniqueness on relationships proves awkward in practice, the implementation may introduce an internal derived field such as `scopedId = workspaceId + ":" + id` for relationships only. That is an implementation detail and must not leak into the public SDK API.

### Snapshot, Restore, And Compensation Semantics

This phase must enforce strong workspace-scoped recovery behavior.

Required rules:

- `captureSnapshot()` on the Neo4j side captures only the current workspace
- `restore(snapshot)` on the Neo4j side clears and rebuilds only the current workspace
- PostgreSQL restore already acts on one workspace-scoped provider, so it remains workspace-local
- compensation after a failed Neo4j projection must restore only the current workspace in both stores
- no restore path may delete or rewrite another workspace’s graph data in the shared Neo4j database

The current global Neo4j restore behavior that truncates the full database is not compatible with this design and must be removed from the multi-workspace path.

The same workspace-local rule applies to snapshot APIs exposed by the runtime.

This phase should define the minimum public snapshot surface as:

- `saveSnapshot(String workspaceId, Path path)`
- `restoreSnapshot(String workspaceId, Path path)`

These methods operate only on the addressed workspace.

Builder behavior:

- the existing `loadFromSnapshot(path)` builder entry point may remain only for legacy single-workspace `storage(...)` mode
- multi-workspace `workspaceStorage(...)` mode must not rely on builder-time snapshot restore because it has no workspace input

Autosnapshot behavior:

- autosnapshot remains in phase
- autosnapshot paths are still configured once, but the effective persisted path must be derived per workspace inside the workspace-aware provider layer
- autosnapshot for workspace `A` must never overwrite workspace `B`

### Concurrency Model

The current mixed PostgreSQL + Neo4j provider uses one provider-wide lock. That is too coarse for one runtime serving many workspaces.

The new model should use locking per workspace:

- one workspace write/restore/compensation path excludes other writes in the same workspace
- different workspaces may proceed concurrently

This can be implemented through a `workspaceId -> lock` mapping in the workspace-aware provider layer.

Lock ownership should live with the concrete `WorkspaceStorageProvider` implementation because that layer owns:

- workspace provider caching
- workspace provider lifecycle
- the guarantee that one workspace resolves to one shared provider instance and one shared lock domain

The critical requirement is:

- no compensation restore for workspace `A` may overlap another successful write to workspace `A`
- writes for workspace `A` must not unnecessarily block workspace `B`

### Query And Graph Management Execution

Query strategies and graph-management flows should not be redesigned around workspace-specific store method signatures. Instead:

- external API receives `workspaceId`
- `LightRag` resolves `WorkspaceScope`
- `LightRag` resolves a workspace-scoped provider
- the existing query/indexing/graph-management logic runs against that provider

This keeps workspace propagation explicit at the runtime boundary without forcing every store SPI to grow a `workspaceId` parameter.

### Vector Backend Compatibility

This phase should keep vector concerns abstract enough that a future Milvus backend remains feasible.

Guidelines:

- keep `namespace` as the logical vector partitioning concept
- keep workspace resolution in provider assembly, not in the public `VectorStore` method signatures
- avoid hard-wiring the entire design to PostgreSQL vector snapshot semantics

The current SDK assumes vector state can be fully enumerated and embedded in snapshots. That works well for PostgreSQL and in-memory providers, but it may not map cleanly to future external vector stores. The multi-workspace redesign should therefore avoid deepening that assumption beyond what is already required for the current provider set.

This phase does not need a new vector SPI, but the implementation plan should preserve room for splitting “vector search capability” from “full snapshot capability” later.

### Package And Component Shape

Recommended additions:

- `io.github.lightragjava.api.WorkspaceScope`
- `io.github.lightragjava.storage.WorkspaceStorageProvider`
- `io.github.lightragjava.storage.WorkspaceScopedAtomicStorageProvider` or equivalent workspace-scoped provider implementation
- `io.github.lightragjava.storage.neo4j.WorkspaceScopedNeo4jGraphStore`

Likely touched components:

- `LightRag`
- `LightRagBuilder`
- `IndexingPipeline`
- `DeletionPipeline`
- `GraphManagementPipeline`
- query strategies / query engine construction
- `PostgresNeo4jStorageProvider`
- Spring starter workspace factory and related configuration wiring

## Testing Strategy

Required coverage:

- one `LightRag` instance can serve two workspaces with explicit `workspaceId`
- PostgreSQL data remains isolated by derived table prefix per workspace
- Neo4j reads and writes are isolated by `workspaceId` inside one shared database
- relation placeholder nodes never cross workspace boundaries
- workspace `A` restore does not change workspace `B`
- workspace `A` failed Neo4j projection compensates only workspace `A`
- concurrent writes to different workspaces do not block each other through one global lock
- query results for one workspace never surface graph records from another workspace
- graph-management APIs operate correctly across multiple workspaces from one runtime instance
- Spring starter path can resolve workspace-scoped providers for `postgres-neo4j`

Provider-backed coverage should include at minimum:

- in-memory single-workspace compatibility
- PostgreSQL multi-workspace provider routing
- PostgreSQL + shared Neo4j multi-workspace routing

## Delivery Criteria

This phase is successful when:

- callers can use one `LightRag` instance across multiple workspaces
- every externally visible data operation requires `workspaceId`
- runtime snapshot APIs are workspace-explicit and builder-time snapshot restore is restricted to legacy single-workspace mode
- PostgreSQL remains physically isolated per workspace without a schema redesign
- Neo4j safely hosts multiple workspaces in one database
- workspace-scoped restore and compensation are proven by tests
- the resulting provider/runtime boundary is clean enough to support a future external vector backend without redoing the workspace API model
