---
name: mysql
description: "Skill for the Mysql area of lightrag-java. 186 symbols across 34 files."
---

# Mysql

186 symbols | 34 files | Cohesion: 72%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how saveEntity, saveRelation, allEntities work
- Modifying mysql-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | delegatesMySqlMilvusNeo4jWritesThroughStorageCoordinator, topLevelBatchGraphWritesUseSingleAdapterApplyPerRecordType, rollsBackMySqlRowsWhenProjectionFailsAfterCommit, restoreRestoresMySqlRowsWhenGraphRestoreFails, rollsBackStateWhenMilvusSaveFailsAfterCommit (+38) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | close, close, close, close, close (+14) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java` | applyMissingMigrations, replayAppliedMigrations, migrations, versionOneStatements, versionTwoStatements (+13) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphSnapshotStore.java` | readDocumentSnapshot, readChunkSnapshot, readInstant, saveChunks, delete (+6) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlDocumentGraphJournalStore.java` | listDocumentJournals, listChunkJournals, readDocumentJournal, readChunkJournal, normalizeDocumentId (+5) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlStoresTest.java` | documentChunkAndStatusStoresRoundTripRecords, storesIsolateRowsByWorkspaceId, taskAndTaskDocumentStoresRoundTripRecords, bootstrapRejectsLegacySchemaWithoutWorkspaceColumns, bootstrapSupportsLongTablePrefixesWithoutOverflowingIndexNames (+4) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlJsonCodec.java` | readStringMap, readStringList, readExtractedEntityList, readExtractedRelationList, readJson (+4) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlTaskStore.java` | save, delete, load, list, readTask (+1) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderSdkIntegrationTest.java` | commitsAcrossMySqlMilvusAndNeo4jStoresUsingOfficialSdkBackends, restoreReplacesStateAndSupportsSemanticKeywordAndHybridSearchUsingOfficialSdk, newProvider, uniqueScopeId, awaitMatches (+1) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java` | close, withTransaction, rollback, restoreAutoCommit, get |

## Entry Points

Start here when exploring this area:

- **`saveEntity`** (Method) — `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java:774`
- **`saveRelation`** (Method) — `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java:785`
- **`allEntities`** (Method) — `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java:818`
- **`graphStore`** (Method) — `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java:884`
- **`saveAll`** (Method) — `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java:981`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `saveEntity` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 774 |
| `saveRelation` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 785 |
| `allEntities` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 818 |
| `graphStore` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 884 |
| `saveAll` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 981 |
| `list` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 993 |
| `saveAllEnriched` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 1001 |
| `list` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 1079 |
| `vectorStore` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java` | 1089 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStorageAdapter.java` | 104 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/RelationalStorageAdapter.java` | 91 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | 141 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/VectorStorageAdapter.java` | 134 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 233 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 732 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 741 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 778 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 826 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java` | 268 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 226 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Assemble → StorageException` | cross_community | 7 |
| `Assemble → StorageException` | cross_community | 7 |
| `AddChunkIfPresent → StorageException` | cross_community | 7 |
| `AddChunkIfPresent → StorageException` | cross_community | 7 |
| `LoadAdjacent → StorageException` | cross_community | 7 |
| `LoadAdjacent → StorageException` | cross_community | 7 |
| `RestoreSnapshot → Open` | cross_community | 7 |
| `RestoreSnapshot → Apply` | cross_community | 7 |
| `RestoreSnapshot → StorageException` | cross_community | 7 |
| `SupportingChunks → StorageException` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Postgres | 22 calls |
| Indexing | 14 calls |
| Boot | 7 calls |
| Neo4j | 6 calls |
| Storage | 3 calls |
| Query | 2 calls |
| Memory | 2 calls |
| Api | 2 calls |

## How to Explore

1. `gitnexus_context({name: "saveEntity"})` — see callers and callees
2. `gitnexus_query({query: "mysql"})` — find related execution flows
3. Read key files listed above for implementation details
