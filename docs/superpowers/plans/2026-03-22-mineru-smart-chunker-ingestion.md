# MinerU SmartChunker Ingestion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add raw-file ingestion to `lightrag-java` with automatic `MinerU -> Tika -> plain` parsing, document-type-aware chunk orchestration, manual regex chunking, and V3 parent/child retrieval while keeping existing `ingest(List<Document>)` behavior stable.

**Architecture:** Keep the current `Document` ingest path as the compatibility path and add a second public raw-source ingest entry that normalizes files into `ParsedDocument`, resolves `DocumentType`, selects an effective chunking mode, and only then hands chunks to the existing indexing/query pipeline. Deliver the feature in three layers: V1 text-path parsing + orchestration, V2 structured-block SmartChunker templates, V3 parent/child retrieval and diagnostics. Because this repository does not contain the product console UI, the "UI" part of the spec is implemented here as public ingest options, debug metadata, Spring configuration, and demo upload behavior.

**Tech Stack:** Java 17, Gradle, Jackson, OkHttp, Apache Tika, JUnit 5, AssertJ, MockWebServer, existing LightRAG storage/query pipeline, Spring Boot starter/demo

---

## File Structure

- Create: `lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/api/ChunkGranularity.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/RawDocumentSource.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeHint.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedDocument.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedBlock.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingOrchestrator.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/PlainTextParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/TikaFallbackParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruApiClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruSelfHostedClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruDocumentAdapter.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentType.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingMode.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingStrategyOverride.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingResult.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkRule.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkerConfig.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunker.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildProfile.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkSet.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkBuilder.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/ParentChunkExpander.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/ChunkStore.java`
- Modify: `lightrag-core/build.gradle.kts`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java`
- Modify: `README.md`
- Modify: `README_zh.md`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentParsingOrchestratorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/MineruParsingProviderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/RegexChunkerTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/ChunkingOrchestratorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/ParentChildChunkBuilderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

## Task 1: Public Raw-Source Ingest Entry And V1 Plain-Text Path

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/api/ChunkGranularity.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/RawDocumentSource.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeHint.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedDocument.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedBlock.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingOrchestrator.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/PlainTextParsingProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentParsingOrchestratorTest.java`

- [ ] **Step 1: Write the failing public-ingest API tests**

```java
@Test
void ingestsUtf8MarkdownSourceWithoutCallingMineruOrTika() {
    var rag = testLightRag();
    var source = RawDocumentSource.bytes("guide.md", "# Title\nBody".getBytes(StandardCharsets.UTF_8));

    rag.ingestSources(List.of(source), DocumentIngestOptions.defaults());

    assertThat(chunkTexts(rag)).isNotEmpty();
}

@Test
void keepsLegacyDocumentIngestApiUnchanged() {
    var rag = testLightRag();
    rag.ingest(List.of(new Document("doc-1", "Title", "body", Map.of())));
    assertThat(chunkTexts(rag)).isNotEmpty();
}
```

- [ ] **Step 2: Run the new API tests and confirm they fail**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.api.LightRagBuilderTest" \
  --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest"
```

Expected: FAIL because raw-source models, public ingest overloads, and plain-text parsing do not exist yet.

- [ ] **Step 3: Implement the minimal raw-source path**

```java
public record RawDocumentSource(
    String sourceId,
    String fileName,
    String mediaType,
    byte[] bytes,
    Map<String, String> metadata
) {}

public record DocumentIngestOptions(
    DocumentTypeHint documentTypeHint,
    ChunkGranularity chunkGranularity
) {}
```

Implementation notes:
- Keep `LightRag.ingest(List<Document>)` untouched.
- Add a second public entry, for example `LightRag.ingestSources(List<RawDocumentSource>, DocumentIngestOptions)`.
- In V1, `DocumentParsingOrchestrator` only needs to support plain text directly plus placeholders for complex-parser routing added in Task 2.
- `ParsedDocument.plainText` must always be populated.
- Do not expose parser backend selection in the public API.
- Do not expose regex override or parent/child toggles yet; Task 1 only exposes the default UI fields from the spec: `documentType` and `chunkGranularity`.

- [ ] **Step 4: Re-run the targeted tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.api.LightRagBuilderTest" \
  --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest"
```

Expected: PASS for the new raw-source entry and the legacy `Document` path.

- [ ] **Step 5: Commit the public raw-source API**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/ChunkGranularity.java \
        lightrag-core/src/main/java/io/github/lightragjava/types/RawDocumentSource.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeHint.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedDocument.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ParsedBlock.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingProvider.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingOrchestrator.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/PlainTextParsingProvider.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java \
        lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentParsingOrchestratorTest.java
git commit -m "feat: add raw source ingest entrypoint"
```

## Task 2: MinerU Clients, Tika Fallback, And Failure Metadata

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/TikaFallbackParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruApiClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruSelfHostedClient.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruParsingProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruDocumentAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingOrchestrator.java`
- Modify: `lightrag-core/build.gradle.kts`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/MineruParsingProviderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentParsingOrchestratorTest.java`

- [ ] **Step 1: Write the failing complex-parser tests**

```java
@Test
void fallsBackToTikaWhenMineruIsUnavailableForPdf() { ... }

@Test
void failsInsteadOfUsingTikaWhenImageMineruParsingFails() { ... }

@Test
void recordsParseModeBackendAndErrorReasonOnDowngrade() { ... }
```

- [ ] **Step 2: Run the parser tests and verify failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.MineruParsingProviderTest" \
  --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest"
```

Expected: FAIL because MinerU clients, Tika fallback, and downgrade metadata do not exist.

- [ ] **Step 3: Implement MinerU and Tika routing**

```java
interface MineruClient {
    MineruParseResult parse(RawDocumentSource source);
}

final class DocumentParsingOrchestrator {
    ParsedDocument parse(RawDocumentSource source) {
        if (isPlainText(source)) return plainTextProvider.parse(source);
        try {
            return mineruProvider.parse(source);
        } catch (MineruUnavailableException ex) {
            if (isImage(source)) throw ex;
            return tikaProvider.parse(source).withMetadata("parse_error_reason", ex.getMessage());
        }
    }
}
```

Implementation notes:
- Add Apache Tika dependencies only to `lightrag-core`.
- Treat `content_list.json` as the primary structured source and `full.md` as MinerU-internal text fallback.
- Preserve `parse_mode`, `parse_backend`, and `parse_error_reason` on every parsed output.
- For images, do not fake success with empty OCR text.

- [ ] **Step 4: Re-run parser tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.MineruParsingProviderTest" \
  --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest"
```

Expected: PASS for MinerU API mode, self-hosted mode, Tika fallback, and image failure behavior.

- [ ] **Step 5: Commit the parser integration**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/TikaFallbackParsingProvider.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruClient.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruApiClient.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruSelfHostedClient.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruParsingProvider.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/MineruDocumentAdapter.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentParsingOrchestrator.java \
        lightrag-core/build.gradle.kts \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/MineruParsingProviderTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentParsingOrchestratorTest.java
git commit -m "feat: add mineru and tika parsing pipeline"
```

## Task 3: V1 Chunking Orchestrator And Regex Strategy

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentType.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingMode.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingStrategyOverride.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingResult.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkRule.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkerConfig.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunker.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/RegexChunkerTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/ChunkingOrchestratorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Write the failing V1 orchestration tests**

```java
@Test
void autoStrategySelectsRegexWhenRulesExistEvenWithoutExplicitOverride() { ... }

@Test
void v1SmartPathChunksUsingParsedDocumentPlainTextInsteadOfStructuredBlocks() { ... }

@Test
void regexChunkerFallsBackToFixedWhenRulesProduceNoValidBoundaries() { ... }
```

- [ ] **Step 2: Run the orchestration tests and confirm failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.RegexChunkerTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest"
```

Expected: FAIL because document typing, `AUTO` resolution, regex chunking, and parsed-document chunk preparation do not exist.

- [ ] **Step 3: Implement the V1 chunking contract**

```java
final class ChunkingOrchestrator {
    ChunkingResult chunk(ParsedDocument document, DocumentType type, ChunkingProfile profile) {
        var initialMode = resolveInitialMode(profile.strategyOverride(), profile.regexConfig());
        return switch (initialMode) {
            case REGEX -> regexChunker.chunk(document.plainText(), type, profile);
            case SMART -> smartTextPath(document.plainText(), type, profile);
            case FIXED -> fixedChunk(document.plainText(), profile);
        };
    }
}
```

Implementation notes:
- Expand `DocumentIngestOptions` here to add advanced overrides:
  - `ChunkingStrategyOverride strategyOverride`
  - `RegexChunkerConfig regexConfig`
- Encode the spec-approved `AUTO` decision table exactly:
  - regex rules present => initial mode `REGEX`
  - otherwise => initial mode `SMART`
- `DocumentType` chooses template behavior, not top-level mode.
- V1 must explicitly use `ParsedDocument.plainText` plus the existing text Smart path.
- Keep semantic merge active only when effective mode is `SMART`.
- Persist `effectiveMode`, `downgradedToFixed`, and fallback reason into chunk metadata for diagnostics.

- [ ] **Step 4: Re-run the targeted V1 tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.RegexChunkerTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest"
```

Expected: PASS for V1 text-path orchestration and regex fallback behavior.

- [ ] **Step 5: Commit the V1 chunking layer**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentType.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentTypeResolver.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingMode.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingStrategyOverride.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingResult.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkRule.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunkerConfig.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/RegexChunker.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/RegexChunkerTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/ChunkingOrchestratorTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java
git commit -m "feat: add chunking orchestrator and regex mode"
```

## Task 4: V2 SmartChunker Structured-Block Path And Type Templates

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Write the failing V2 SmartChunker tests**

```java
@Test
void consumesParsedBlocksWhenStructuredMineruBlocksAreAvailable() { ... }

@Test
void lawTemplateDoesNotMergeAcrossArticles() { ... }

@Test
void qaTemplateKeepsQuestionAndAnswerTogether() { ... }
```

- [ ] **Step 2: Run the SmartChunker tests and verify failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
  --tests "io.github.lightragjava.indexing.SemanticChunkRefinerTest"
```

Expected: FAIL because SmartChunker only understands the current structure parser and has no document-type templates over normalized `ParsedBlock`.

- [ ] **Step 3: Implement V2 structured SmartChunker**

```java
public final class SmartChunker implements Chunker {
    List<Chunk> chunkParsedDocument(ParsedDocument document, DocumentType type, SmartChunkerConfig config) {
        var blocks = document.blocks().isEmpty() ? deriveFallbackBlocks(document.plainText()) : document.blocks();
        return blockPlanner.plan(blocks, type, config);
    }
}
```

Implementation notes:
- Keep the existing standalone `chunk(Document)` API working.
- Add a normalized block-input path for orchestration use.
- Implement `LAW`, `BOOK`, and `QA` template behavior from the spec.
- Harden Chinese sentence splitting, list continuation, and empty-title/empty-section-path fallback.
- Maintain semantic merge boundary checks for `sectionHierarchy`, `contentType`, and table boundaries.

- [ ] **Step 4: Re-run V2 tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
  --tests "io.github.lightragjava.indexing.SemanticChunkRefinerTest"
```

Expected: PASS for structured-block ingestion and template-aware boundaries.

- [ ] **Step 5: Commit the V2 SmartChunker upgrade**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java
git commit -m "feat: add structured smart chunking templates"
```

## Task 5: V3 Parent/Child Storage, Retrieval Expansion, And Diagnostics

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildProfile.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkSet.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkBuilder.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/ParentChunkExpander.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/ChunkStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/ParentChildChunkBuilderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`

- [ ] **Step 1: Write the failing V3 parent/child tests**

```java
@Test
void skipsParentChildBuilderWhenDisabled() { ... }

@Test
void retrievesChildVectorsButReturnsParentContext() { ... }

@Test
void fallsBackToSingleLevelChunksBeforeV3ConfigIsEnabled() { ... }
```

- [ ] **Step 2: Run V3 tests and confirm failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.ParentChildChunkBuilderTest" \
  --tests "io.github.lightragjava.query.NaiveQueryStrategyTest" \
  --tests "io.github.lightragjava.query.LocalQueryStrategyTest" \
  --tests "io.github.lightragjava.query.GlobalQueryStrategyTest"
```

Expected: FAIL because there is no parent/child builder, no parent metadata expansion, and no optional-stage handling.

- [ ] **Step 3: Implement the optional V3 stage**

```java
final class ParentChildChunkBuilder {
    ParentChildChunkSet build(ChunkingResult result, ParentChildProfile profile) { ... }
}

final class ParentChunkExpander {
    List<ScoredChunk> expand(List<ScoredChunk> childHits, ChunkStore chunkStore) { ... }
}
```

Implementation notes:
- Keep `parentChildEnabled=false` as a strict no-op path.
- Add `parentChildEnabled` to `DocumentIngestOptions` in this task, not earlier, so the public API matches the spec's V3 exposure timing.
- Store parent and child chunks in the same `ChunkStore` using metadata keys such as `parent_chunk_id`, `child_chunk_id`, and `chunk_level`.
- Search still hits child vectors first; expansion loads and de-duplicates parent chunks before context assembly.
- Preserve page, bbox, section path, and effective mode metadata for diagnostics.

- [ ] **Step 4: Re-run V3 tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.ParentChildChunkBuilderTest" \
  --tests "io.github.lightragjava.query.NaiveQueryStrategyTest" \
  --tests "io.github.lightragjava.query.LocalQueryStrategyTest" \
  --tests "io.github.lightragjava.query.GlobalQueryStrategyTest"
```

Expected: PASS for optional parent/child execution and parent-expanded retrieval.

- [ ] **Step 5: Commit the V3 retrieval layer**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildProfile.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkSet.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ParentChildChunkBuilder.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/ParentChunkExpander.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/DocumentIngestOptions.java \
        lightrag-core/src/main/java/io/github/lightragjava/storage/ChunkStore.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/MixQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/query/ContextAssembler.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/ParentChildChunkBuilderTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java
git commit -m "feat: add parent child retrieval expansion"
```

## Task 6: Spring Configuration, Demo Upload Path, And Documentation

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java`
- Modify: `README.md`
- Modify: `README_zh.md`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: Write the failing starter and demo tests**

```java
@Test
void bindsMineruAndChunkingDefaultsFromSpringProperties() { ... }

@Test
void uploadControllerFallsBackToTikaWhenMineruIsUnavailableForDocx() { ... }

@Test
void uploadControllerRejectsImageWhenMineruParsingFails() { ... }
```

- [ ] **Step 2: Run starter/demo tests and verify failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest" \
  :lightrag-spring-boot-demo:test --tests "io.github.lightragjava.demo.UploadControllerTest" \
  --tests "io.github.lightragjava.demo.DemoApplicationTest"
```

Expected: FAIL because there are no Spring properties or demo upload paths for MinerU/Tika-backed parsing.

- [ ] **Step 3: Implement starter/demo integration**

```java
public static class ParsingProperties {
    private boolean enabled = true;
    private MineruProperties mineru = new MineruProperties();
}
```

Implementation notes:
- Add starter properties for MinerU API/self-hosted settings and default ingest chunking options.
- Keep parser mode internal; expose only business-level defaults where possible.
- Update demo upload to build `RawDocumentSource` objects instead of forcing everything into plain text before SDK ingest.
- In docs, explain:
  - supported file types
  - MinerU preferred path
  - Tika fallback rules
  - no image OCR fallback outside MinerU
  - regex/manual strategy vs Smart strategy

- [ ] **Step 4: Run the starter/demo/documentation verification**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest" \
  :lightrag-spring-boot-demo:test --tests "io.github.lightragjava.demo.UploadControllerTest" \
  --tests "io.github.lightragjava.demo.DemoApplicationTest"
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.ParentChildChunkBuilderTest"
```

Expected: PASS for starter binding, demo upload flow, and final cross-layer regressions.

- [ ] **Step 5: Commit starter/demo/docs integration**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java \
        lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java \
        README.md README_zh.md
git commit -m "feat: wire smart ingestion into starter and demo"
```

## Final Verification

- [ ] **Step 1: Run the focused end-to-end regression suite**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.api.LightRagBuilderTest" \
  --tests "io.github.lightragjava.indexing.DocumentParsingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.MineruParsingProviderTest" \
  --tests "io.github.lightragjava.indexing.RegexChunkerTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
  --tests "io.github.lightragjava.indexing.ParentChildChunkBuilderTest" \
  --tests "io.github.lightragjava.query.NaiveQueryStrategyTest" \
  --tests "io.github.lightragjava.query.LocalQueryStrategyTest" \
  --tests "io.github.lightragjava.query.GlobalQueryStrategyTest"
./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest"
./gradlew :lightrag-spring-boot-demo:test --tests "io.github.lightragjava.demo.UploadControllerTest" \
  --tests "io.github.lightragjava.demo.DemoApplicationTest"
```

Expected: PASS with V1/V2/V3 coverage and no regression on legacy ingest paths.

- [ ] **Step 2: Run the broader safety net**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test
```

Expected: PASS for the full core module before merge.

- [ ] **Step 3: Create the final implementation commit or merge batch**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git status
git log --oneline --decorate -n 10
```

Expected: only planned feature commits remain; no accidental unrelated revert.
