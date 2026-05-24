---
name: storage
description: "Skill for the Storage area of lightrag-java. 271 symbols across 61 files."
---

# Storage

271 symbols | 61 files | Cohesion: 73%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how InMemoryStorageProvider, StorageCoordinator, MySqlMilvusNeo4jStorageProvider work
- Modifying storage-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | writeAtomically, toWrites, toWrites, StorageCoordinator, list (+18) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | documentStore, chunkStore, writeInTransaction, restoreCount, graphStore (+17) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | writeAtomically, saveEntity, saveRelation, writeInTransaction, PostgresNeo4jStorageProvider (+11) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryDocumentGraphStoresTest.java` | inMemorySnapshotStorePersistsDocumentAndChunkSnapshots, snapshotStoreCopiesInputAndReturnsImmutableChunkList, snapshotStoreDeleteRemovesDocumentAndChunks, snapshotStoreRejectsChunkDocumentIdMismatch, snapshotStoreReplacesChunks (+8) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/InMemoryStorageProvider.java` | writeAtomically, snapshot, InMemoryStorageProvider, documentGraphSnapshotStore, InMemorySnapshotStore (+6) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | writeAtomically, withWorkspaceExclusiveLock, newAtomicView, PostgresStorageProvider, search (+5) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | writeAtomically, saveEntity, saveRelation, withExclusiveProviderLock, withExclusiveAdvisoryScope (+4) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | buildsWithStorageAssembly, writeAtomically, writeAtomically, writeAtomically, ProviderWithBrokenGraphStores (+4) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | materializeDocumentState, writeChunkMaterialization, saveGraph, distinctIds, mergeEntity (+3) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/RelationalStorageAdapter.java` | writeInTransaction, execute, taskStore, taskStageStore, snapshotStore (+3) |

## Entry Points

Start here when exploring this area:

- **`InMemoryStorageProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/InMemoryStorageProvider.java:25`
- **`StorageCoordinator`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java:11`
- **`MySqlMilvusNeo4jStorageProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java:34`
- **`PostgresNeo4jStorageProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java:30`
- **`PostgresMilvusNeo4jStorageProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java:34`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `InMemoryStorageProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/InMemoryStorageProvider.java` | 25 |
| `StorageCoordinator` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | 11 |
| `MySqlMilvusNeo4jStorageProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 34 |
| `PostgresNeo4jStorageProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 30 |
| `PostgresMilvusNeo4jStorageProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 34 |
| `PostgresStorageProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | 34 |
| `ProjectionGraphStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 745 |
| `Neo4jGraphStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java` | 8 |
| `FakeGraphStorageAdapter` | Class | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 155 |
| `InMemorySnapshotStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/InMemoryStorageProvider.java` | 281 |
| `NoopSnapshotStore` | Class | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 264 |
| `WorkspaceSnapshotStore` | Class | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/SpringWorkspaceStorageProvider.java` | 379 |
| `MySqlRelationalStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java` | 26 |
| `PostgresProviderRelationalAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 531 |
| `PostgresRelationalStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresRelationalStorageAdapter.java` | 28 |
| `FakeRelationalStorageAdapter` | Class | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 22 |
| `MilvusVectorStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java` | 12 |
| `PostgresVectorStorageAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresVectorStorageAdapter.java` | 11 |
| `FakeVectorStorageAdapter` | Class | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 204 |
| `InMemoryDocumentGraphJournalStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentGraphJournalStore.java` | 11 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 9 |
| `SearchAdditionalDirectChunkMatches → EscapeFilterLiteral` | cross_community | 9 |
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 8 |
| `SearchAdditionalDirectChunkMatches → Split` | cross_community | 8 |
| `SearchAdditionalDirectChunkMatches → Search` | cross_community | 7 |
| `SearchAdditionalDirectChunkMatches → DotProduct` | cross_community | 7 |
| `MaterializeDocumentState → LoadDocument` | cross_community | 6 |
| `MaterializeDocumentState → ListChunks` | cross_community | 6 |
| `MaterializeDocumentState → ListDocumentJournals` | cross_community | 6 |
| `MaterializeDocumentState → ListChunkJournals` | cross_community | 6 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Indexing | 63 calls |
| Postgres | 57 calls |
| Api | 35 calls |
| Mysql | 18 calls |
| Query | 12 calls |
| Task | 11 calls |
| Neo4j | 9 calls |
| Memory | 7 calls |

## How to Explore

1. `gitnexus_context({name: "InMemoryStorageProvider"})` — see callers and callees
2. `gitnexus_query({query: "storage"})` — find related execution flows
3. Read key files listed above for implementation details
