---
name: neo4j
description: "Skill for the Neo4j area of lightrag-java. 139 symbols across 27 files."
---

# Neo4j

139 symbols | 27 files | Cohesion: 75%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how WorkspaceStoreProjection, expand, pathKey work
- Modifying neo4j-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProviderTest.java` | delegatesThroughInjectedGraphAdapterAndKeepsPostgresVectorStore, exposesStableTopLevelStoresAndDistinctAtomicViewStores, topLevelGraphWritesMirrorIntoAtomicGraphView, topLevelBatchGraphWritesUseSingleProjectionApplyPerRecordType, commitsAcrossPostgresAndNeo4jStores (+33) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java` | savesAndLoadsEntityWithinWorkspaceOnly, savesRelationWithWorkspaceScopedPlaceholders, savesRelationWithUpstreamPropertyNames, listsEntitiesRelationsAndNeighborsWithinCurrentWorkspace, restoreOnlyReplacesCurrentWorkspace (+14) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java` | loadEntities, loadRelations, findRelations, findRelations, scopedId (+12) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | loadEntities, loadRelations, findRelations, findRelations, close (+8) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java` | findRelations, findRelations, readRelation, initializeRelationBuckets, addRelation (+2) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | findRelations, findRelations, documentStore, chunkStore, vectorStore (+1) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | findRelations, findRelations, saveEntities, saveRelations |
| `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | findRelations, findRelations, graphStore |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStore.java` | findRelations, findRelations, close |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/DocumentIngestorTest.java` | ingestsDocumentsIntoPostgresStorageProvider, rollsBackPostgresDocumentWriteWhenChunkPersistenceFails, PostgresSnapshotStore |

## Entry Points

Start here when exploring this area:

- **`WorkspaceStoreProjection`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java:68`
- **`expand`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathRetriever.java:48`
- **`pathKey`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathRetriever.java:91`
- **`findRelations`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java:65`
- **`findRelations`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java:67`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `WorkspaceStoreProjection` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 68 |
| `Projection` | Interface | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 59 |
| `expand` | Method | `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathRetriever.java` | 48 |
| `pathKey` | Method | `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathRetriever.java` | 91 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java` | 65 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java` | 67 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | 381 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | 389 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java` | 106 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java` | 125 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 676 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 681 |
| `loadEntities` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 100 |
| `loadRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 110 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 125 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 130 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStore.java` | 51 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStore.java` | 56 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 329 |
| `findRelations` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 334 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `IngestSources → DocumentStore` | cross_community | 5 |
| `CreateRelation → DocumentStore` | cross_community | 4 |
| `CreateEntity → DocumentStore` | cross_community | 4 |
| `WriteChunkMaterialization → DocumentStore` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Postgres | 36 calls |
| Api | 21 calls |
| Storage | 16 calls |
| Indexing | 15 calls |
| Query | 6 calls |
| Boot | 2 calls |
| Mysql | 1 calls |
| Milvus | 1 calls |

## How to Explore

1. `gitnexus_context({name: "WorkspaceStoreProjection"})` — see callers and callees
2. `gitnexus_query({query: "neo4j"})` — find related execution flows
3. Read key files listed above for implementation details
