# Java LightRAG SDK Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Java SDK that implements the v1 LightRAG-style indexing and retrieval pipeline with in-memory storage, file snapshot persistence, OpenAI-compatible model adapters, and `local` / `global` / `hybrid` / `mix` query modes.

**Architecture:** Use a single Gradle Java project with a small public facade and internal packages for types, storage, indexing, query, model adapters, and persistence. Keep storage and model dependencies behind narrow SPI interfaces so later database and service integrations do not force API redesign.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, AssertJ, Jackson, OkHttp, MockWebServer

---

## Prerequisites

- A local Java 17 JDK must be available on `PATH`.
- A local Gradle 8.14.3 installation is required once to generate the wrapper. After `gradlew` exists, all remaining commands in this plan must use the wrapper instead of a system Gradle.

## File Structure

Planned repository layout and file responsibilities:

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `.gitignore`
- Create: `README.md`
- Create: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Create: `src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryMode.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryResult.java`
- Create: `src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Create: `src/main/java/io/github/lightragjava/exception/ModelException.java`
- Create: `src/main/java/io/github/lightragjava/exception/StorageException.java`
- Create: `src/main/java/io/github/lightragjava/exception/ExtractionException.java`
- Create: `src/main/java/io/github/lightragjava/exception/QueryExecutionException.java`
- Create: `src/main/java/io/github/lightragjava/types/Document.java`
- Create: `src/main/java/io/github/lightragjava/types/Chunk.java`
- Create: `src/main/java/io/github/lightragjava/types/Entity.java`
- Create: `src/main/java/io/github/lightragjava/types/Relation.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractionResult.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractedEntity.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractedRelation.java`
- Create: `src/main/java/io/github/lightragjava/types/QueryContext.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredEntity.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredRelation.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredChunk.java`
- Create: `src/main/java/io/github/lightragjava/model/ChatModel.java`
- Create: `src/main/java/io/github/lightragjava/model/EmbeddingModel.java`
- Create: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java`
- Create: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleEmbeddingModel.java`
- Create: `src/main/java/io/github/lightragjava/storage/StorageProvider.java`
- Create: `src/main/java/io/github/lightragjava/storage/DocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/ChunkStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/GraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/VectorStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/SnapshotStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/InMemoryStorageProvider.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryDocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryChunkStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryGraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryVectorStore.java`
- Create: `src/main/java/io/github/lightragjava/persistence/FileSnapshotStore.java`
- Create: `src/main/java/io/github/lightragjava/persistence/SnapshotManifest.java`
- Create: `src/main/java/io/github/lightragjava/persistence/SnapshotPayload.java`
- Create: `src/main/java/io/github/lightragjava/indexing/Chunker.java`
- Create: `src/main/java/io/github/lightragjava/indexing/FixedWindowChunker.java`
- Create: `src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Create: `src/main/java/io/github/lightragjava/indexing/KnowledgeExtractor.java`
- Create: `src/main/java/io/github/lightragjava/indexing/GraphAssembler.java`
- Create: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Create: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Create: `src/main/java/io/github/lightragjava/query/QueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Create: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/FixedWindowChunkerTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/GraphAssemblerTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/KnowledgeExtractorTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryDocumentStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryChunkStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryGraphStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryStorageProviderTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryVectorStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/persistence/FileSnapshotStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Create: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleEmbeddingModelTest.java`
- Create: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

## Chunk 1: Repository Bootstrap And Public API Skeleton

### Task 1: Bootstrap the Gradle project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `.gitignore`
- Create: `README.md`

- [ ] **Step 1: Verify the wrapper is missing**

Run: `./gradlew tasks`
Expected: FAIL because the repository has no Gradle wrapper yet.

- [ ] **Step 2: Add the build and wrapper scaffolding**

Create `build.gradle.kts` with:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testImplementation("org.assertj:assertj-core:3.27.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}
```

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "lightrag-java"
```

Generate the wrapper:

```bash
gradle wrapper --gradle-version 8.14.3
```

- [ ] **Step 3: Verify the wrapper works**

Run: `./gradlew tasks`
Expected: PASS and list the standard Gradle task groups.

- [ ] **Step 4: Commit the bootstrap files**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties .gitignore README.md
git commit -m "build: bootstrap Gradle project"
```

### Task 2: Add the public API skeleton and shared config

**Files:**
- Create: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Create: `src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryMode.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Create: `src/main/java/io/github/lightragjava/api/QueryResult.java`
- Create: `src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Create: `src/main/java/io/github/lightragjava/exception/ModelException.java`
- Create: `src/main/java/io/github/lightragjava/exception/StorageException.java`
- Create: `src/main/java/io/github/lightragjava/exception/ExtractionException.java`
- Create: `src/main/java/io/github/lightragjava/exception/QueryExecutionException.java`
- Create: `src/main/java/io/github/lightragjava/model/ChatModel.java`
- Create: `src/main/java/io/github/lightragjava/model/EmbeddingModel.java`
- Create: `src/main/java/io/github/lightragjava/storage/StorageProvider.java`
- Create: `src/main/java/io/github/lightragjava/storage/DocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/ChunkStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/GraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/VectorStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/SnapshotStore.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Expand the failing builder tests**

Create `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java` with:

```java
class LightRagBuilderTest {
    @Test
    void buildsWithRequiredDependencies() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build();

        assertThat(rag).isNotNull();
    }

    @Test
    void rejectsMissingChatModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingEmbeddingModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the builder test suite**

Run: `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: FAIL with missing API classes and builders.

- [ ] **Step 3: Implement the minimal public API**

Define:

- `LightRag.builder()`
- `LightRagBuilder.chatModel(...)`
- `LightRagBuilder.embeddingModel(...)`
- `LightRagBuilder.storage(...)`
- `LightRagBuilder.loadFromSnapshot(Path path)`
- `LightRagBuilder.build()`
- `QueryMode` enum with `LOCAL`, `GLOBAL`, `HYBRID`, `MIX`
- immutable `QueryRequest` and `QueryResult`
- `ChatModel`, `EmbeddingModel`, and `StorageProvider` as minimal SPI interfaces
- store interfaces with the minimal read/write/list methods required for indexing, query, and snapshot export/import
- exception types extending `RuntimeException`

Keep test doubles local to `LightRagBuilderTest` as nested classes so this task does not depend on production in-memory storage yet.

- [ ] **Step 4: Re-run the builder test suite**

Run: `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit the API skeleton**

```bash
git add src/main/java/io/github/lightragjava/api src/main/java/io/github/lightragjava/config src/main/java/io/github/lightragjava/exception src/main/java/io/github/lightragjava/model src/main/java/io/github/lightragjava/storage src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: add public API skeleton"
```

### Task 3: Add domain types

**Files:**
- Create: `src/main/java/io/github/lightragjava/types/Document.java`
- Create: `src/main/java/io/github/lightragjava/types/Chunk.java`
- Create: `src/main/java/io/github/lightragjava/types/Entity.java`
- Create: `src/main/java/io/github/lightragjava/types/Relation.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractionResult.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractedEntity.java`
- Create: `src/main/java/io/github/lightragjava/types/ExtractedRelation.java`
- Create: `src/main/java/io/github/lightragjava/types/QueryContext.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredEntity.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredRelation.java`
- Create: `src/main/java/io/github/lightragjava/types/ScoredChunk.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Write failing tests for document identity and extraction contracts**

Add tests such as:

```java
@Test
void extractedRelationRequiresSourceTargetAndType() {
    assertThatThrownBy(() -> new ExtractedRelation("Alice", "", "works_with", "", null))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void documentRequiresNonBlankId() {
    assertThatThrownBy(() -> new Document(" ", "Title", "Body", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the targeted tests**

Run: `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: FAIL with missing type classes.

- [ ] **Step 3: Implement the minimal types and interfaces**

Key requirements:

- `Document.id` is required and validated
- `ExtractionResult` contains `entities`, `relations`, `warnings`
- `ExtractedRelation` requires source name, target name, and type
- scored types carry IDs plus numeric score values used by retrieval merges

- [ ] **Step 4: Re-run the targeted tests**

Run: `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit the domain and SPI layer**

```bash
git add src/main/java/io/github/lightragjava/types src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: add domain types"
```

## Chunk 2: Indexing Pipeline And In-Memory Storage

### Task 4: Implement in-memory storage contracts

**Files:**
- Create: `src/main/java/io/github/lightragjava/storage/InMemoryStorageProvider.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryDocumentStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryChunkStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryGraphStore.java`
- Create: `src/main/java/io/github/lightragjava/storage/memory/InMemoryVectorStore.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryDocumentStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryChunkStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryGraphStoreTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryStorageProviderTest.java`
- Create: `src/test/java/io/github/lightragjava/storage/InMemoryVectorStoreTest.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Write failing storage tests**

Add tests for:

```java
@Test
void storesAndLoadsDocumentsById() {}

@Test
void storesAndLoadsChunksById() {}

@Test
void graphStoreReturnsOneHopRelationsForEntity() {}

@Test
void providerExposesConsistentStoreInstances() {}

@Test
void storesAndQueriesTopKChunkVectors() {}

@Test
void storesAndQueriesTopKEntityVectors() {}

@Test
void storesAndQueriesTopKRelationVectors() {}
```

- [ ] **Step 2: Run the storage tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.InMemoryDocumentStoreTest --tests io.github.lightragjava.storage.InMemoryChunkStoreTest --tests io.github.lightragjava.storage.InMemoryGraphStoreTest --tests io.github.lightragjava.storage.InMemoryStorageProviderTest --tests io.github.lightragjava.storage.InMemoryVectorStoreTest`
Expected: FAIL because in-memory store implementations do not exist.

- [ ] **Step 3: Implement minimal in-memory stores**

Requirements:

- use thread-safe collections
- preserve lookup by document ID, chunk ID, entity ID, and relation ID
- return deterministic order for equal scores
- keep graph edges addressable by endpoint IDs
- expose collection-style reads needed by snapshot save and restore

- [ ] **Step 4: Re-run the storage tests**

Run: `./gradlew test --tests io.github.lightragjava.storage.InMemoryDocumentStoreTest --tests io.github.lightragjava.storage.InMemoryChunkStoreTest --tests io.github.lightragjava.storage.InMemoryGraphStoreTest --tests io.github.lightragjava.storage.InMemoryStorageProviderTest --tests io.github.lightragjava.storage.InMemoryVectorStoreTest`
Expected: PASS

- [ ] **Step 5: Commit the in-memory stores**

```bash
git add src/main/java/io/github/lightragjava/storage src/test/java/io/github/lightragjava/storage/InMemoryDocumentStoreTest.java src/test/java/io/github/lightragjava/storage/InMemoryChunkStoreTest.java src/test/java/io/github/lightragjava/storage/InMemoryGraphStoreTest.java src/test/java/io/github/lightragjava/storage/InMemoryStorageProviderTest.java src/test/java/io/github/lightragjava/storage/InMemoryVectorStoreTest.java src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: add in-memory storage implementations"
```

### Task 5: Implement chunking and document ingestion

**Files:**
- Create: `src/main/java/io/github/lightragjava/indexing/Chunker.java`
- Create: `src/main/java/io/github/lightragjava/indexing/FixedWindowChunker.java`
- Create: `src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Create: `src/test/java/io/github/lightragjava/indexing/FixedWindowChunkerTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Write failing chunking tests**

Test cases:

- splits long content into ordered chunks
- keeps configured overlap
- rejects duplicate document IDs already in storage
- rejects duplicate IDs in a single batch

- [ ] **Step 2: Run the indexing tests**

Run: `./gradlew test --tests io.github.lightragjava.indexing.FixedWindowChunkerTest --tests io.github.lightragjava.indexing.DocumentIngestorTest`
Expected: FAIL because indexing classes do not exist.

- [ ] **Step 3: Implement chunking and ingest validation**

Minimal implementation details:

- `FixedWindowChunker` operates on character windows for v1
- `DocumentIngestor` validates IDs before mutating storage
- chunk IDs use deterministic `documentId + order` composition

- [ ] **Step 4: Re-run the indexing tests**

Run: `./gradlew test --tests io.github.lightragjava.indexing.FixedWindowChunkerTest --tests io.github.lightragjava.indexing.DocumentIngestorTest`
Expected: PASS

- [ ] **Step 5: Commit chunking and ingest**

```bash
git add src/main/java/io/github/lightragjava/indexing src/test/java/io/github/lightragjava/indexing/FixedWindowChunkerTest.java src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java
git commit -m "feat: add chunking and document ingest"
```

### Task 6: Implement extraction normalization and graph assembly

**Files:**
- Create: `src/main/java/io/github/lightragjava/indexing/KnowledgeExtractor.java`
- Create: `src/main/java/io/github/lightragjava/indexing/GraphAssembler.java`
- Create: `src/test/java/io/github/lightragjava/indexing/KnowledgeExtractorTest.java`
- Create: `src/test/java/io/github/lightragjava/indexing/GraphAssemblerTest.java`

- [ ] **Step 1: Write failing extraction and merge tests**

Test cases:

- drops blank extracted entity names
- drops relations with missing endpoints
- merges entities by normalized name
- merges entities by explicit aliases
- merges relations by normalized endpoints plus type

- [ ] **Step 2: Run the extraction and graph tests**

Run: `./gradlew test --tests io.github.lightragjava.indexing.KnowledgeExtractorTest --tests io.github.lightragjava.indexing.GraphAssemblerTest`
Expected: FAIL because extraction and graph assembly implementations do not exist.

- [ ] **Step 3: Implement normalization and merge rules**

Minimal implementation details:

- parser accepts structured JSON from `ChatModel`
- normalization trims whitespace and normalizes merge keys to lowercase
- original casing is preserved on the first accepted entity name
- explicit aliases participate in the same entity merge key set
- missing relation weight defaults to `1.0`

- [ ] **Step 4: Re-run the extraction and graph tests**

Run: `./gradlew test --tests io.github.lightragjava.indexing.KnowledgeExtractorTest --tests io.github.lightragjava.indexing.GraphAssemblerTest`
Expected: PASS

- [ ] **Step 5: Commit extraction normalization and graph assembly**

```bash
git add src/main/java/io/github/lightragjava/indexing src/test/java/io/github/lightragjava/indexing/KnowledgeExtractorTest.java src/test/java/io/github/lightragjava/indexing/GraphAssemblerTest.java
git commit -m "feat: add extraction normalization and graph assembly"
```

### Task 7: Implement the indexing pipeline facade

**Files:**
- Create: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Create: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write a failing end-to-end ingest test**

Add a test like:

```java
@Test
void ingestBuildsChunkEntityRelationAndVectorIndexes() {
    var storage = InMemoryStorageProvider.create();
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(storage)
        .build();

    rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
    assertThat(storage.graphStore().allEntities()).isNotEmpty();
}
```

- [ ] **Step 2: Run the end-to-end ingest test**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`
Expected: FAIL because `LightRag.ingest()` and the indexing pipeline are not wired.

- [ ] **Step 3: Implement `IndexingPipeline` and wire `LightRag.ingest()`**

Requirements:

- ingest documents
- chunk and store content
- embed chunks
- extract entities and relations
- merge graph
- embed entity and relation summaries
- persist snapshot when configured

- [ ] **Step 4: Re-run the end-to-end ingest test**

Run: `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`
Expected: PASS for the ingest assertions

- [ ] **Step 5: Commit the indexing pipeline**

```bash
git add src/main/java/io/github/lightragjava/indexing src/main/java/io/github/lightragjava/api/LightRag.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: wire indexing pipeline"
```

## Chunk 3: Query Strategies, Context Assembly, And Persistence

### Task 8: Implement local and global query strategies

**Files:**
- Create: `src/main/java/io/github/lightragjava/query/QueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Create: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`

- [ ] **Step 1: Write failing local and global strategy tests**

Test cases:

- local uses entity similarity and one-hop neighbors
- global uses relation similarity and endpoint entities
- both trim chunks to `chunkTopK`

- [ ] **Step 2: Run the strategy tests**

Run: `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest`
Expected: FAIL because query strategies do not exist.

- [ ] **Step 3: Implement `LocalQueryStrategy`, `GlobalQueryStrategy`, and `ContextAssembler`**

Requirements:

- `topK` means entities for local
- `topK` means relations for global
- supporting chunk score from graph path uses max contributing graph score
- context assembler produces deterministic prompt sections for entities, relations, and chunks
- `QueryResult.contexts` is populated with the assembled query context returned by the strategy

- [ ] **Step 4: Re-run the strategy tests**

Run: `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest`
Expected: PASS

- [ ] **Step 5: Commit local and global retrieval**

```bash
git add src/main/java/io/github/lightragjava/query src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java
git commit -m "feat: add local and global query strategies"
```

### Task 9: Implement hybrid and mix query strategies

**Files:**
- Create: `src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Create: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Create: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Create: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write failing hybrid and mix tests**

Test cases:

- hybrid merges local and global by ID with max-score retention
- mix merges hybrid chunks with direct chunk vector retrieval
- `QueryMode.MIX` is the default request mode

- [ ] **Step 2: Run the hybrid and mix tests**

Run: `./gradlew test --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: FAIL because hybrid, mix, and query engine wiring do not exist.

- [ ] **Step 3: Implement hybrid, mix, and `LightRag.query()`**

Requirements:

- execute branch strategies independently
- merge by ID
- preserve graph entities and relations from hybrid inside mix
- trim final chunks with `chunkTopK`
- set `QueryRequest` default mode to `MIX`
- call `ChatModel` with assembled context and original question

- [ ] **Step 4: Re-run the hybrid, mix, and end-to-end tests**

Run: `./gradlew test --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: PASS

- [ ] **Step 5: Commit hybrid, mix, and query engine wiring**

```bash
git add src/main/java/io/github/lightragjava/query src/main/java/io/github/lightragjava/api/LightRag.java src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add hybrid and mix query modes"
```

### Task 10: Implement snapshot persistence

**Files:**
- Create: `src/main/java/io/github/lightragjava/persistence/FileSnapshotStore.java`
- Create: `src/main/java/io/github/lightragjava/persistence/SnapshotManifest.java`
- Create: `src/main/java/io/github/lightragjava/persistence/SnapshotPayload.java`
- Create: `src/test/java/io/github/lightragjava/persistence/FileSnapshotStoreTest.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Write failing snapshot tests**

Test cases:

- save writes manifest and repository data
- load restores documents, chunks, graph, and vectors
- save uses atomic replace semantics
- builder load restores storage before `build()`
- successful ingest auto-saves only when snapshot persistence is configured

- [ ] **Step 2: Run the snapshot tests**

Run: `./gradlew test --tests io.github.lightragjava.persistence.FileSnapshotStoreTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: FAIL because snapshot classes do not exist.

- [ ] **Step 3: Implement file snapshots**

Requirements:

- use Jackson for payload serialization
- include schema version and created timestamp
- save through temp file then rename
- `LightRagBuilder.loadFromSnapshot(...)` restores storage before `build()`
- successful `ingest()` triggers save only when a snapshot store is configured

- [ ] **Step 4: Re-run the snapshot tests**

Run: `./gradlew test --tests io.github.lightragjava.persistence.FileSnapshotStoreTest --tests io.github.lightragjava.E2ELightRagTest`
Expected: PASS

- [ ] **Step 5: Commit snapshot persistence**

```bash
git add src/main/java/io/github/lightragjava/persistence src/main/java/io/github/lightragjava/api/LightRagBuilder.java src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java src/test/java/io/github/lightragjava/persistence/FileSnapshotStoreTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add file snapshot persistence"
```

## Chunk 4: OpenAI-Compatible Adapters, Final Verification, And Docs

### Task 11: Implement the OpenAI-compatible adapters

**Files:**
- Create: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java`
- Create: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleEmbeddingModel.java`
- Create: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Create: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleEmbeddingModelTest.java`

- [ ] **Step 1: Write failing adapter tests with MockWebServer**

Test cases:

- chat adapter sends OpenAI-compatible request payload
- chat adapter parses response content
- embedding adapter parses embedding vectors
- non-2xx responses raise `ModelException`
- malformed JSON or missing required response fields raise `ModelException`

- [ ] **Step 2: Run the adapter tests**

Run: `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest --tests io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModelTest`
Expected: FAIL because adapter implementations do not exist.

- [ ] **Step 3: Implement the adapters**

Requirements:

- configurable base URL, model name, and API key
- JSON request and response handling through Jackson
- throw `ModelException` for non-2xx or malformed responses

- [ ] **Step 4: Re-run the adapter tests**

Run: `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest --tests io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModelTest`
Expected: PASS

- [ ] **Step 5: Commit the OpenAI-compatible adapters**

```bash
git add src/main/java/io/github/lightragjava/model/openai src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleEmbeddingModelTest.java
git commit -m "feat: add OpenAI-compatible model adapters"
```

### Task 12: Final end-to-end verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Extend the end-to-end test to cover query modes**

Add assertions that:

- `LOCAL` returns entity-centric context
- `GLOBAL` returns relation-centric context
- `HYBRID` merges graph results
- `MIX` merges graph and chunk vector retrieval
- each `QueryResult` exposes non-empty `contexts` for successful queries

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: Document the public SDK usage**

Add to `README.md`:

- project purpose
- quick-start code sample
- snapshot usage example
- current v1 limitations

- [ ] **Step 4: Run the full test suite again**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit the final SDK slice**

```bash
git add README.md src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "docs: finalize Java LightRAG SDK usage"
```

## Verification Commands

Run these before claiming the implementation is complete:

- `./gradlew test`
- `git status --short`
- `git log --oneline --decorate -5`

## Notes For Execution

- Keep the project single-module for v1. Do not introduce Gradle subprojects unless the current package structure becomes unmanageable during implementation.
- Prefer records for immutable data carriers such as `Document`, `Chunk`, `Entity`, `Relation`, and query result types.
- Keep extraction prompt text and JSON parsing logic close together in `KnowledgeExtractor` for v1; do not introduce a generic prompt framework.
- Use fake in-memory model implementations in unit tests instead of network calls except for the dedicated OpenAI adapter tests.
- Do not add database backends, rerankers, or an HTTP service in this plan.
