---
name: memory
description: "Skill for the Memory area of lightrag-java. 34 symbols across 9 files."
---

# Memory

34 symbols | 9 files | Cohesion: 76%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how toVectorRecord, saveAll, search work
- Modifying memory-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | saveAll, search, list, snapshot, score (+2) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryTaskStore.java` | save, delete, withWriteLock, load, list (+1) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryTaskStageStore.java` | save, deleteByTask, withWriteLock, listByTask, withReadLock |
| `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | captureSnapshot, apply, restore, captureSnapshot |
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | delete, restore, withWriteLock, snapshot |
| `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryVectorStoreTest.java` | storesAndQueriesTopKChunkVectors, storesAndQueriesTopKEntityVectors, storesAndQueriesTopKRelationVectors |
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryChunkStore.java` | restore, snapshot |
| `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStore.java` | restore, snapshot |
| `lightrag-core/src/main/java/io/github/lightrag/storage/HybridVectorStore.java` | toVectorRecord |

## Entry Points

Start here when exploring this area:

- **`toVectorRecord`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/HybridVectorStore.java:44`
- **`saveAll`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java:31`
- **`search`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java:58`
- **`list`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java:101`
- **`snapshot`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java:118`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `toVectorRecord` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/HybridVectorStore.java` | 44 |
| `saveAll` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 31 |
| `search` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 58 |
| `list` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 101 |
| `snapshot` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 118 |
| `score` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 155 |
| `semanticScore` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 163 |
| `dotProduct` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryVectorStore.java` | 196 |
| `captureSnapshot` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 215 |
| `apply` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 220 |
| `restore` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryChunkStore.java` | 101 |
| `delete` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | 38 |
| `restore` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | 47 |
| `withWriteLock` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | 56 |
| `restore` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStore.java` | 72 |
| `restore` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 96 |
| `snapshot` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryChunkStore.java` | 97 |
| `snapshot` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStatusStore.java` | 43 |
| `snapshot` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryDocumentStore.java` | 68 |
| `captureSnapshot` | Method | `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java` | 73 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Postgres | 5 calls |
| Indexing | 2 calls |
| Query | 1 calls |

## How to Explore

1. `gitnexus_context({name: "toVectorRecord"})` — see callers and callees
2. `gitnexus_query({query: "memory"})` — find related execution flows
3. Read key files listed above for implementation details
