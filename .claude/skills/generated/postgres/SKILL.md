---
name: postgres
description: "Skill for the Postgres area of lightrag-java. 709 symbols across 92 files."
---

# Postgres

709 symbols | 92 files | Cohesion: 78%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how InMemoryDocumentStore, MySqlDocumentStore, LockedDocumentStore work
- Modifying postgres-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java` | restoreReplacesDocumentGraphStores, persistsDocumentGraphStateAcrossProviderRestart, graphSnapshot, chunkGraphSnapshot, documentGraphJournal (+47) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | withWorkspaceSharedLock, captureDocumentGraphState, list, list, allEntities (+46) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProviderTest.java` | persistsDocumentGraphStateAcrossProviderRestart, exposesStableTopLevelStoresAndDistinctAtomicViewStores, honorsProvidedLockForTopLevelDocumentWrites, queryReadsDoNotHoldWorkspaceAdvisoryLock, delegatesAtomicWriteToStorageCoordinatorAndPersistsPostgresGraphBaseline (+40) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | buildFromConfigs, buildFromDataSourceConfigs, buildFromProjections, buildMilvusPayloads, allEntities (+31) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | list, list, list, list, allEntities (+30) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | vectorStore, withReadLock, withWriteLock, allEntities, allRelations (+29) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java` | applyMissingMigrations, replayAppliedMigrations, migrations, latestSchemaVersion, versionThreeStatements (+16) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | list, list, allEntities, allRelations, list (+12) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java` | allEntities, allRelations, saveEntity, saveRelation, upsertEntity (+10) |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/DocumentIngestorTest.java` | rollsBackDocumentAndChunkWritesWhenChunkStoreSaveFails, rejectsDocumentIdsThatAppearDuringAtomicWrite, AtomicFailureStorageProvider, insertDocumentBeforeAtomicWrite, list (+9) |

## Entry Points

Start here when exploring this area:

- **`InMemoryDocumentStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStore.java:11`
- **`MySqlDocumentStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentStore.java:11`
- **`LockedDocumentStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java:443`
- **`LockedDocumentStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java:340`
- **`PostgresDocumentStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentStore.java:11`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `InMemoryDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStore.java` | 11 |
| `MySqlDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentStore.java` | 11 |
| `LockedDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 443 |
| `LockedDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 340 |
| `PostgresDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentStore.java` | 11 |
| `LockedDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 474 |
| `LockedDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | 464 |
| `InMemoryDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | 11 |
| `MySqlDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentStatusStore.java` | 12 |
| `LockedDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 504 |
| `LockedDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 401 |
| `PostgresDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresDocumentStatusStore.java` | 12 |
| `LockedDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 535 |
| `LockedDocumentStatusStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | 596 |
| `InMemoryTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryTaskDocumentStore.java` | 11 |
| `LockedTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 583 |
| `MySqlTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlTaskDocumentStore.java` | 12 |
| `LockedTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 480 |
| `LockedTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 614 |
| `LockedTaskDocumentStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | 675 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 9 |
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 8 |
| `Assemble → StorageException` | cross_community | 7 |
| `Assemble → StorageException` | cross_community | 7 |
| `AddChunkIfPresent → StorageException` | cross_community | 7 |
| `AddChunkIfPresent → StorageException` | cross_community | 7 |
| `LoadAdjacent → StorageException` | cross_community | 7 |
| `LoadAdjacent → StorageException` | cross_community | 7 |
| `RestoreSnapshot → Normalize` | cross_community | 7 |
| `RestoreSnapshot → Open` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Api | 198 calls |
| Query | 120 calls |
| Indexing | 116 calls |
| Storage | 64 calls |
| Mysql | 47 calls |
| Neo4j | 30 calls |
| Boot | 12 calls |
| Memory | 2 calls |

## How to Explore

1. `gitnexus_context({name: "InMemoryDocumentStore"})` — see callers and callees
2. `gitnexus_query({query: "postgres"})` — find related execution flows
3. Read key files listed above for implementation details
