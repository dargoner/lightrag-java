# Java LightRAG Structured Query SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new public SDK query method that returns a stable structured result containing answer, contexts, references, entities, relations, and chunks.

**Architecture:** Keep the existing `LightRag.query(...)` flow unchanged and add an additive structured result path. Reuse the current `QueryEngine` retrieval pipeline, then map `ScoredEntity`, `ScoredRelation`, and `ScoredChunk` into new public API records without exposing internal `QueryContext`.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing `LightRag` public API, existing `QueryEngine`

---

### Task 1: Lock Down the Public Structured Result Contract with Failing Tests

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write a failing end-to-end SDK test for structured entity, relation, and chunk payloads**

Add a new test to `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` near other query API tests:

```java
@Test
void queryStructuredReturnsAnswerAndStructuredMatches() {
    QueryRequest request = QueryRequest.builder()
        .query("Which company does Alice work at?")
        .mode(QueryMode.LOCAL)
        .topK(5)
        .chunkTopK(5)
        .includeReferences(true)
        .build();

    StructuredQueryResult result = rag.queryStructured(WORKSPACE, request);

    assertThat(result.answer()).isNotBlank();
    assertThat(result.entities()).isNotEmpty();
    assertThat(result.relations()).isNotEmpty();
    assertThat(result.chunks()).isNotEmpty();
    assertThat(result.entities().get(0).name()).isNotBlank();
    assertThat(result.relations().get(0).srcId()).isNotBlank();
    assertThat(result.relations().get(0).tgtId()).isNotBlank();
    assertThat(result.chunks().get(0).documentId()).isNotBlank();
}
```

- [ ] **Step 2: Run the new end-to-end test to verify it fails**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.queryStructuredReturnsAnswerAndStructuredMatches"
```

Expected:
- FAIL with compile errors because `StructuredQueryResult` and `LightRag.queryStructured(...)` do not exist yet

- [ ] **Step 3: Write a failing query engine test for query flag semantics**

Add new tests to `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`:

```java
@Test
void queryStructuredRejectsStreamingRequests() {
    QueryRequest request = QueryRequest.builder()
        .query("test")
        .mode(QueryMode.NAIVE)
        .stream(true)
        .build();

    assertThatThrownBy(() -> queryEngine.queryStructured(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stream");
}

@Test
void queryStructuredReturnsPromptForOnlyNeedPromptRequests() {
    QueryRequest request = QueryRequest.builder()
        .query("test")
        .mode(QueryMode.NAIVE)
        .onlyNeedPrompt(true)
        .build();

    StructuredQueryResult result = queryEngine.queryStructured(request);

    assertThat(result.answer()).contains("test");
}
```

- [ ] **Step 4: Run the new query engine tests to verify they fail**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest.queryStructuredRejectsStreamingRequests" --tests "io.github.lightrag.query.QueryEngineTest.queryStructuredReturnsPromptForOnlyNeedPromptRequests"
```

Expected:
- FAIL with compile errors because `queryStructured(...)` does not exist yet

- [ ] **Step 5: Commit the failing tests**

```bash
git add lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "test: add structured query sdk coverage"
```

### Task 2: Add Public Structured Result Models

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryResult.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryEntity.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryRelation.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryChunk.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`

- [ ] **Step 1: Create `StructuredQueryEntity`**

Create `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryEntity.java`:

```java
package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryEntity(
    String id,
    String name,
    String type,
    String description,
    List<String> aliases,
    List<String> sourceChunkIds,
    double score
) {
    public StructuredQueryEntity {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        type = type == null ? "" : type.strip();
        description = description == null ? "" : description.strip();
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
```

- [ ] **Step 2: Create `StructuredQueryRelation`**

Create `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryRelation.java`:

```java
package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryRelation(
    String id,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    List<String> sourceChunkIds,
    String filePath,
    double score
) {
    public StructuredQueryRelation {
        id = requireNonBlank(id, "id");
        srcId = requireNonBlank(srcId, "srcId");
        tgtId = requireNonBlank(tgtId, "tgtId");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        filePath = filePath == null ? "" : filePath.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
```

- [ ] **Step 3: Create `StructuredQueryChunk`**

Create `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryChunk.java`:

```java
package io.github.lightrag.api;

import java.util.Map;
import java.util.Objects;

public record StructuredQueryChunk(
    String id,
    String documentId,
    String text,
    int tokenCount,
    int order,
    Map<String, String> metadata,
    double score
) {
    public StructuredQueryChunk {
        id = requireNonBlank(id, "id");
        documentId = requireNonBlank(documentId, "documentId");
        text = Objects.requireNonNull(text, "text");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be non-negative");
        }
        if (order < 0) {
            throw new IllegalArgumentException("order must be non-negative");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
```

- [ ] **Step 4: Create `StructuredQueryResult`**

Create `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryResult.java`:

```java
package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryResult(
    String answer,
    List<QueryResult.Context> contexts,
    List<QueryResult.Reference> references,
    List<StructuredQueryEntity> entities,
    List<StructuredQueryRelation> relations,
    List<StructuredQueryChunk> chunks
) {
    public StructuredQueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }
}
```

- [ ] **Step 5: Run the failing end-to-end test again**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.queryStructuredReturnsAnswerAndStructuredMatches"
```

Expected:
- FAIL because `LightRag.queryStructured(...)` and `QueryEngine.queryStructured(...)` still do not exist

- [ ] **Step 6: Commit the public API model types**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryResult.java lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryEntity.java lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryRelation.java lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryChunk.java
git commit -m "feat: add structured query result models"
```

### Task 3: Implement QueryEngine Structured Query Mapping

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Add `QueryEngine.queryStructured(QueryRequest)` entry point**

In `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`, add a new method next to `query(QueryRequest)`:

```java
public StructuredQueryResult queryStructured(QueryRequest request) {
    var resolvedQuery = Objects.requireNonNull(request, "request");
    if (resolvedQuery.stream()) {
        throw new IllegalArgumentException("queryStructured does not support stream=true");
    }
    return runStructuredQuery(resolvedQuery);
}
```

- [ ] **Step 2: Extract shared query execution into a reusable internal method**

Refactor the existing `query(QueryRequest)` implementation so that retrieval, chunk limiting, context assembly, answer selection, and reference generation can be reused.

Add a private internal record in `QueryEngine.java`:

```java
private record ExecutedQuery(
    String answer,
    List<QueryResult.Context> contexts,
    List<QueryResult.Reference> references,
    QueryContext context
) {
}
```

Add a private method:

```java
private ExecutedQuery executeQuery(QueryRequest request) {
    var resolvedQuery = automaticQueryKeywordExtraction
        ? queryKeywordExtractor.resolveKeywords(request)
        : request;
    var useMultiHop = shouldUseMultiHop(resolvedQuery);
    var strategy = useMultiHop ? multiHopStrategy : strategies.get(resolvedQuery.mode());
    if (strategy == null) {
        throw new IllegalStateException("No query strategy configured for mode: " + resolvedQuery.mode());
    }
    var retrievalRequest = rerankEnabled(resolvedQuery) && !useMultiHop
        ? expandChunkRequest(resolvedQuery, rerankCandidateMultiplier)
        : resolvedQuery;
    var retrievedContext = strategy.retrieve(retrievalRequest);
    var rerankedChunks = rerankEnabled(resolvedQuery) && !useMultiHop
        ? rerankChunks(resolvedQuery, retrievedContext.matchedChunks())
        : retrievedContext.matchedChunks();
    var filteredChunks = QueryMetadataFilterSupport.filterChunks(resolvedQuery, rerankedChunks);
    var reusableMultiHopContext = useMultiHop
        && !retrievedContext.assembledContext().isBlank()
        && sameChunkIds(retrievedContext.matchedChunks(), filteredChunks);
    var finalChunks = QueryBudgeting.limitChunks(
        filteredChunks,
        remainingChunkBudget(
            resolvedQuery,
            retrievedContext,
            reusableMultiHopContext ? retrievedContext.assembledContext() : null
        )
    );
    var finalContext = new QueryContext(
        retrievedContext.matchedEntities(),
        retrievedContext.matchedRelations(),
        finalChunks,
        ""
    );
    var assembledContext = reusableMultiHopContext
        && sameChunkIds(filteredChunks, finalChunks)
        ? retrievedContext.assembledContext()
        : contextAssembler.assemble(finalContext);
    var assembledQueryContext = new QueryContext(
        finalContext.matchedEntities(),
        finalContext.matchedRelations(),
        finalContext.matchedChunks(),
        assembledContext
    );
    var references = QueryReferences.fromChunks(assembledQueryContext.matchedChunks(), resolvedQuery.includeReferences());
    var chatRequest = new ChatModel.ChatRequest(
        buildSystemPrompt(resolvedQuery, assembledContext),
        resolvedQuery.query(),
        resolvedQuery.conversationHistory()
    );
    var answer = resolvedQuery.onlyNeedContext() && !resolvedQuery.onlyNeedPrompt()
        ? assembledContext
        : resolvedQuery.onlyNeedPrompt()
            ? renderStandardPrompt(chatRequest)
            : pathAwareAnswerSynthesizer.shouldUseTwoStage(resolvedQuery, chatRequest.systemPrompt())
                ? generateTwoStageAnswer(responseModel, chatRequest)
                : responseModel.generate(chatRequest);
    return new ExecutedQuery(answer, references.contexts(), references.references(), assembledQueryContext);
}
```

Constraints:

- keep existing `query(QueryRequest)` behavior unchanged
- keep current `onlyNeedContext`, `onlyNeedPrompt`, and `includeReferences` semantics unchanged
- keep streaming handling in `query(QueryRequest)` only

- [ ] **Step 3: Map internal scored results to structured API records**

Add private mapper methods in `QueryEngine.java`:

```java
private static StructuredQueryEntity toStructuredEntity(ScoredEntity entity) {
    return new StructuredQueryEntity(
        entity.entityId(),
        entity.entity().name(),
        entity.entity().type(),
        entity.entity().description(),
        entity.entity().aliases(),
        entity.entity().sourceChunkIds(),
        entity.score()
    );
}

private static StructuredQueryRelation toStructuredRelation(ScoredRelation relation) {
    return new StructuredQueryRelation(
        relation.relationId(),
        relation.relation().srcId(),
        relation.relation().tgtId(),
        relation.relation().keywords(),
        relation.relation().description(),
        relation.relation().weight(),
        relation.relation().sourceChunkIds(),
        relation.relation().filePath(),
        relation.score()
    );
}

private static StructuredQueryChunk toStructuredChunk(ScoredChunk chunk) {
    return new StructuredQueryChunk(
        chunk.chunkId(),
        chunk.chunk().documentId(),
        chunk.chunk().text(),
        chunk.chunk().tokenCount(),
        chunk.chunk().order(),
        chunk.chunk().metadata(),
        chunk.score()
    );
}
```

- [ ] **Step 4: Return `StructuredQueryResult` from the new path**

Implement:

```java
private StructuredQueryResult runStructuredQuery(QueryRequest request) {
    var executed = executeQuery(request);
    return new StructuredQueryResult(
        executed.answer(),
        executed.contexts(),
        executed.references(),
        executed.context().matchedEntities().stream().map(QueryEngine::toStructuredEntity).toList(),
        executed.context().matchedRelations().stream().map(QueryEngine::toStructuredRelation).toList(),
        executed.context().matchedChunks().stream().map(QueryEngine::toStructuredChunk).toList()
    );
}
```

- [ ] **Step 5: Run the targeted query engine tests**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest.queryStructuredRejectsStreamingRequests" --tests "io.github.lightrag.query.QueryEngineTest.queryStructuredReturnsPromptForOnlyNeedPromptRequests"
```

Expected:
- PASS

- [ ] **Step 6: Commit the query engine implementation**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: add structured query engine result mapping"
```

### Task 4: Expose the Structured Query SDK Method on LightRag

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java`

- [ ] **Step 1: Add the public `LightRag.queryStructured(...)` method**

In `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`, add:

```java
public StructuredQueryResult queryStructured(String workspaceId, QueryRequest request) {
    var scope = resolveScope(workspaceId);
    return newQueryEngine(resolveProvider(scope)).queryStructured(request);
}
```

- [ ] **Step 2: Extend the end-to-end SDK test to verify field mapping**

In `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`, update the structured query test to assert:

```java
assertThat(result.entities()).allSatisfy(entity -> {
    assertThat(entity.id()).isNotBlank();
    assertThat(entity.name()).isNotBlank();
    assertThat(entity.score()).isFinite();
});

assertThat(result.relations()).allSatisfy(relation -> {
    assertThat(relation.id()).isNotBlank();
    assertThat(relation.srcId()).isNotBlank();
    assertThat(relation.tgtId()).isNotBlank();
    assertThat(relation.score()).isFinite();
});

assertThat(result.chunks()).allSatisfy(chunk -> {
    assertThat(chunk.id()).isNotBlank();
    assertThat(chunk.documentId()).isNotBlank();
    assertThat(chunk.text()).isNotBlank();
    assertThat(chunk.score()).isFinite();
});
```

- [ ] **Step 3: Add workspace isolation coverage for `queryStructured(...)`**

In `lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java`, add a test:

```java
@Test
void queryStructuredReturnsWorkspaceScopedResults() {
    var request = QueryRequest.builder()
        .query("workspace-specific query")
        .mode(QueryMode.LOCAL)
        .onlyNeedContext(true)
        .build();

    var left = rag.queryStructured("workspace-a", request);
    var right = rag.queryStructured("workspace-b", request);

assertThat(left.chunks()).isNotEqualTo(right.chunks());
}
```

- [ ] **Step 4: Run the SDK-facing test set**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.queryStructuredReturnsAnswerAndStructuredMatches" --tests "io.github.lightrag.api.LightRagWorkspaceTest.queryStructuredReturnsWorkspaceScopedResults"
```

Expected:
- PASS

- [ ] **Step 5: Commit the public LightRag entry point**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java
git commit -m "feat: expose structured query sdk method"
```

### Task 5: Final Verification and Commit Hygiene

**Files:**
- Modify: any files touched above

- [ ] **Step 1: Run the final verification suite**

Run:

```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest" --tests "io.github.lightrag.api.LightRagWorkspaceTest" --tests "io.github.lightrag.E2ELightRagTest"
```

Expected:
- PASS

- [ ] **Step 2: Inspect git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected:
- only structured query SDK implementation, tests, and plan changes are present

- [ ] **Step 3: Commit the implementation plan**

```bash
git add docs/superpowers/plans/2026-04-22-java-lightrag-structured-query-sdk.md
git commit -m "docs: add structured query sdk implementation plan"
```
