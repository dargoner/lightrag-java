---
name: persistence
description: "Skill for the Persistence area of lightrag-java. 15 symbols across 3 files."
---

# Persistence

15 symbols | 3 files | Cohesion: 64%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how save, writeAtomically, moveAtomically work
- Modifying persistence-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | save, writeAtomically, moveAtomically, createParentDirectories, payloadPathFor (+5) |
| `lightrag-core/src/test/java/io/github/lightrag/persistence/FileSnapshotStoreTest.java` | saveWritesManifestAndRepositoryData, loadRestoresDocumentsChunksGraphAndVectors, saveUsesAtomicReplaceSemantics, sampleSnapshot |
| `lightrag-core/src/main/java/io/github/lightrag/persistence/SnapshotPayload.java` | fromSnapshot |

## Entry Points

Start here when exploring this area:

- **`save`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:24`
- **`writeAtomically`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:84`
- **`moveAtomically`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:90`
- **`createParentDirectories`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:98`
- **`payloadPathFor`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:112`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `save` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 24 |
| `writeAtomically` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 84 |
| `moveAtomically` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 90 |
| `createParentDirectories` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 98 |
| `payloadPathFor` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 112 |
| `fromSnapshot` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/SnapshotPayload.java` | 46 |
| `load` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 45 |
| `readManifest` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 65 |
| `readPayload` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 76 |
| `openExistingFile` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 105 |
| `normalize` | Method | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 121 |
| `saveWritesManifestAndRepositoryData` | Method | `lightrag-core/src/test/java/io/github/lightrag/persistence/FileSnapshotStoreTest.java` | 33 |
| `loadRestoresDocumentsChunksGraphAndVectors` | Method | `lightrag-core/src/test/java/io/github/lightrag/persistence/FileSnapshotStoreTest.java` | 49 |
| `saveUsesAtomicReplaceSemantics` | Method | `lightrag-core/src/test/java/io/github/lightrag/persistence/FileSnapshotStoreTest.java` | 60 |
| `sampleSnapshot` | Method | `lightrag-core/src/test/java/io/github/lightrag/persistence/FileSnapshotStoreTest.java` | 83 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Api | 3 calls |
| Indexing | 1 calls |
| Postgres | 1 calls |
| Query | 1 calls |

## How to Explore

1. `gitnexus_context({name: "save"})` — see callers and callees
2. `gitnexus_query({query: "persistence"})` — find related execution flows
3. Read key files listed above for implementation details
