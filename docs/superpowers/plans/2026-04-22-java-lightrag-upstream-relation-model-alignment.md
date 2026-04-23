# Java LightRAG Upstream Relation Model Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Java-specific `relationType` relation model with an upstream-aligned undirected endpoint-pair model across APIs, core logic, PostgreSQL, MySQL, Neo4j, Milvus, docs, and version metadata.

**Architecture:** The implementation introduces a new canonical relation payload centered on `src_id`, `tgt_id`, `keywords`, and a short hash `relation_id`. The write path canonicalizes endpoint order before persisting to graph, relational, and vector stores. Existing relation schemas are treated as incompatible and are rebuilt rather than migrated in place.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, PostgreSQL, MySQL, Neo4j, Milvus, Spring Boot demo

---

## File Structure

### Core domain and API

- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/CreateRelationRequest.java`
- Delete: `lightrag-core/src/main/java/io/github/lightrag/api/EditRelationRequest.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/UpdateRelationRequest.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/DeleteRelationRequest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/GraphRelation.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/RelationCanonicalizer.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/types/Relation.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/types/reasoning/HopEvidence.java`

### Core write and query paths

- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphManagementPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/HybridVectorPayloads.java`
- Modify: query classes that expose relation type semantics, starting with:
  - `lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java`
  - `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java`
  - `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathRetriever.java`
  - `lightrag-core/src/main/java/io/github/lightrag/query/ReasoningContextAssembler.java`

### Storage

- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`

### Demo, docs, version

- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/GraphController.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/GraphControllerTest.java`
- Modify: `README.md`
- Modify: `gradle.properties`

### Tests

- Modify or create focused tests in:
  - `lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

---

### Task 1: Lock the new relation contract with failing API and domain tests

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightrag/api/RelationRequestContractTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/CreateRelationRequest.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/UpdateRelationRequest.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/DeleteRelationRequest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/GraphRelation.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/RelationCanonicalizer.java`

- [ ] **Step 1: Write the failing request-contract test**

```java
class RelationRequestContractTest {
    @Test
    void createRelationRequestRejectsBlankKeywordsAndLongEndpoints() {
        assertThatThrownBy(() -> new CreateRelationRequest("Alice", "Bob", "   ", "desc", 1.0d, "", ""))
            .hasMessageContaining("keywords must not be blank");

        var longName = "A".repeat(257);
        assertThatThrownBy(() -> new CreateRelationRequest(longName, "Bob", "works", "desc", 1.0d, "", ""))
            .hasMessageContaining("sourceEntityName must be at most 256 characters");
    }

    @Test
    void canonicalizerSortsEndpointsAndBuildsStableRelationId() {
        var canonical = RelationCanonicalizer.canonicalize("bob", "Alice");
        assertThat(canonical.srcId()).isEqualTo("Alice");
        assertThat(canonical.tgtId()).isEqualTo("bob");
        assertThat(canonical.relationId()).startsWith("rel-");
        assertThat(canonical.relationId()).hasSizeLessThanOrEqualTo(64);
    }
}
```

- [ ] **Step 2: Run the focused test to confirm the old API fails the new contract**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.RelationRequestContractTest"`

Expected: FAIL with missing constructor fields, missing `RelationCanonicalizer`, and old `relationType` semantics.

- [ ] **Step 3: Replace relation request/domain API with the new shape**

```java
public record CreateRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) { /* validate blank keywords, 256-char endpoint cap, finite weight */ }

public record UpdateRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    Double weight,
    String sourceId,
    String filePath
) { /* endpoint validation + at least one mutable field present */ }

public record DeleteRelationRequest(String sourceEntityName, String targetEntityName) { }
```

```java
public record GraphRelation(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) { }
```

- [ ] **Step 4: Add canonicalization helper**

```java
public final class RelationCanonicalizer {
    public static CanonicalRelationRef canonicalize(String source, String target) {
        var normalizedSource = requireEndpoint(source, "source");
        var normalizedTarget = requireEndpoint(target, "target");
        if (normalizedSource.equals(normalizedTarget)) {
            throw new IllegalArgumentException("self-loop relations are not allowed");
        }
        var ordered = Stream.of(normalizedSource, normalizedTarget).sorted().toList();
        var relationId = "rel-" + md5Hex(ordered.get(0) + ordered.get(1));
        return new CanonicalRelationRef(relationId, ordered.get(0), ordered.get(1));
    }
}
```

- [ ] **Step 5: Re-run the focused contract test**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.RelationRequestContractTest"`

Expected: PASS

- [ ] **Step 6: Commit the contract and API rename**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/CreateRelationRequest.java \
        lightrag-core/src/main/java/io/github/lightrag/api/UpdateRelationRequest.java \
        lightrag-core/src/main/java/io/github/lightrag/api/DeleteRelationRequest.java \
        lightrag-core/src/main/java/io/github/lightrag/api/GraphRelation.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/RelationCanonicalizer.java \
        lightrag-core/src/test/java/io/github/lightrag/api/RelationRequestContractTest.java
git commit -m "feat: align relation api with upstream model"
```

### Task 2: Rebuild graph-management and relation write paths around endpoint-pair identity

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphManagementPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/types/Relation.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/types/reasoning/HopEvidence.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`

- [ ] **Step 1: Add failing end-to-end tests for undirected endpoint-pair behavior**

```java
@Test
void createRelationTreatsReverseEndpointsAsSameRelationAndMergesKeywords() {
    rag.createRelation("default", new CreateRelationRequest("Alice", "Bob", "works_with", "first", 1.0d, "", ""));
    rag.createRelation("default", new CreateRelationRequest("Bob", "Alice", "reports_to", "second", 1.0d, "", ""));

    var graph = rag.inspectDocumentGraph("default");
    assertThat(graph.relations()).singleElement().satisfies(relation -> {
        assertThat(relation.keywords()).isEqualTo("reports_to,works_with");
        assertThat(relation.srcId()).isEqualTo("Alice");
        assertThat(relation.tgtId()).isEqualTo("Bob");
    });
}
```

- [ ] **Step 2: Run the end-to-end relation tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest"`

Expected: FAIL because current pipeline still keys by `relationType`.

- [ ] **Step 3: Replace `relationType` identity logic in the graph-management pipeline**

```java
var canonical = RelationCanonicalizer.canonicalize(sourceEntity.id(), targetEntity.id());
var existing = loadRelationByEndpoints(snapshot.relations(), canonical.srcId(), canonical.tgtId());

var mergedKeywords = mergeCommaSeparated(existing == null ? "" : existing.keywords(), request.keywords());
var mergedDescription = mergeDescriptions(existing == null ? "" : existing.description(), request.description());

return new GraphRelation(
    canonical.relationId(),
    canonical.srcId(),
    canonical.tgtId(),
    mergedKeywords,
    mergedDescription,
    mergedWeight,
    mergedSourceId,
    mergedFilePath
);
```

- [ ] **Step 4: Replace update/delete methods on `LightRag`**

```java
public GraphRelation updateRelation(String workspaceId, UpdateRelationRequest request) { ... }

public void deleteRelation(String workspaceId, DeleteRelationRequest request) { ... }
```

Delete the old `editRelation(...)` API and remove all references to `currentRelationType`.

- [ ] **Step 5: Update relation evidence/query-facing types**

```java
public record HopEvidence(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight
) { }
```

- [ ] **Step 6: Re-run focused API and E2E tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagWorkspaceTest" --tests "io.github.lightrag.E2ELightRagTest"`

Expected: PASS for new undirected endpoint-pair tests; old `editRelation` tests removed or rewritten.

- [ ] **Step 7: Commit the core write-path rewrite**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/GraphManagementPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/types/Relation.java \
        lightrag-core/src/main/java/io/github/lightrag/types/reasoning/HopEvidence.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java \
        lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java
git commit -m "feat: use endpoint-pair relation identity"
```

### Task 3: Align relational and in-memory relation stores with the new schema

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryGraphStoreTest.java`

- [ ] **Step 1: Write failing storage tests for the new columns and uniqueness**

```java
@Test
void postgresGraphStorePersistsKeywordsAndSourceIdByRelationId() {
    var relation = new Relation("rel-123", "Alice", "Bob", "reports_to,works_with", "desc", 2.0d, "chunk-1<SEP>chunk-2", "file-a");
    graphStore.saveRelation(toRecord(relation));
    assertThat(graphStore.loadRelation("rel-123")).isPresent();
}
```

- [ ] **Step 2: Run the PostgreSQL and in-memory storage tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest" --tests "io.github.lightrag.storage.InMemoryGraphStoreTest"`

Expected: FAIL because schemas and record mapping still use `type` plus `sourceChunkIds`.

- [ ] **Step 3: Change the store record shape**

```java
record RelationRecord(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) { }
```

Update all persistence adapters to read/write the new record shape.

- [ ] **Step 4: Rewrite PostgreSQL schema and SQL**

```sql
CREATE TABLE IF NOT EXISTS ...relations (
    workspace_id TEXT NOT NULL,
    relation_id VARCHAR(64) NOT NULL,
    src_id TEXT NOT NULL,
    tgt_id TEXT NOT NULL,
    keywords TEXT NOT NULL,
    description TEXT NOT NULL,
    weight DOUBLE PRECISION NOT NULL,
    source_id TEXT NOT NULL,
    file_path TEXT NOT NULL,
    PRIMARY KEY (workspace_id, relation_id),
    UNIQUE (workspace_id, src_id, tgt_id)
)
```

- [ ] **Step 5: Rewrite in-memory and MySQL relation representations**

```java
relationsById.put(record.relationId(), record);
relationsByPair.put(pairKey(record.srcId(), record.tgtId()), record.relationId());
```

For MySQL, rebuild any relation schema bootstrapping to the new columns and drop assumptions about old `type` identity.

- [ ] **Step 6: Re-run the storage tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest" --tests "io.github.lightrag.storage.InMemoryGraphStoreTest"`

Expected: PASS

- [ ] **Step 7: Commit the relational-store alignment**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlSchemaManager.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlRelationalStorageAdapter.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresGraphStoreTest.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryGraphStoreTest.java
git commit -m "feat: align relation stores with upstream schema"
```

### Task 4: Align Neo4j and Milvus technical IDs and payload structure

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/HybridVectorPayloads.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterTest.java`

- [ ] **Step 1: Write failing tests for hashed relation IDs and Milvus field lengths**

```java
@Test
void milvusRelationCollectionUsesShortIdAnd512EndpointFields() {
    var schema = adapter.collectionSchema(1536, "standard");
    assertThat(schema.getField("id").getMaxLength()).isEqualTo(64);
    assertThat(schema.getField("src_id").getMaxLength()).isEqualTo(512);
    assertThat(schema.getField("tgt_id").getMaxLength()).isEqualTo(512);
}
```

- [ ] **Step 2: Run Neo4j and Milvus focused tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest" --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest"`

Expected: FAIL because Neo4j still writes `type` and Milvus still uses `id` length 191.

- [ ] **Step 3: Rewrite Neo4j relation properties around `relation_id`, `src_id`, `tgt_id`, and `keywords`**

```cypher
MERGE (source)-[relation:RELATION {scopedId: $scopedRelationId}]->(target)
SET relation.workspaceId = $workspaceId,
    relation.relation_id = $relationId,
    relation.src_id = $srcId,
    relation.tgt_id = $tgtId,
    relation.keywords = $keywords,
    relation.description = $description,
    relation.weight = $weight,
    relation.source_id = $sourceId,
    relation.file_path = $filePath
```

- [ ] **Step 4: Change Milvus schema compatibility checks**

```java
private boolean hasCompatibleIdField(...) {
    return field != null
        && field.getDataType() == DataType.VarChar
        && Integer.valueOf(64).equals(field.getMaxLength())
        && Boolean.TRUE.equals(field.getIsPrimaryKey())
        && Boolean.FALSE.equals(field.getAutoID());
}
```

```java
CreateCollectionReq.FieldSchema.builder().name("id").dataType(DataType.VarChar).maxLength(64)...
CreateCollectionReq.FieldSchema.builder().name("src_id").dataType(DataType.VarChar).maxLength(512)...
CreateCollectionReq.FieldSchema.builder().name("tgt_id").dataType(DataType.VarChar).maxLength(512)...
```

- [ ] **Step 5: Rebuild relation vector text payload**

```java
private static String relationSummary(Relation relation) {
    return "%s\t%s%n%s%n%s".formatted(
        relation.keywords(),
        relation.srcId(),
        relation.tgtId(),
        relation.description()
    );
}
```

Do not expose `keywords` as a separate Milvus scalar field.

- [ ] **Step 6: Re-run Neo4j and Milvus tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest" --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest"`

Expected: PASS

- [ ] **Step 7: Commit the graph/vector-store alignment**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/HybridVectorPayloads.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterTest.java
git commit -m "feat: align relation graph and vector backends"
```

### Task 5: Update demo, docs, and repository version

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/GraphController.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/GraphControllerTest.java`
- Modify: `README.md`
- Modify: `gradle.properties`

- [ ] **Step 1: Write failing demo-controller tests for the new payload**

```java
mockMvc.perform(post("/graph/relations")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {
              "sourceEntityName": "Alice",
              "targetEntityName": "Bob",
              "keywords": "works_with,reports_to",
              "description": "Cross-team relationship",
              "weight": 1.0
            }
        """))
    .andExpect(status().isOk());
```

- [ ] **Step 2: Run the demo controller tests**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests "io.github.lightrag.demo.GraphControllerTest"`

Expected: FAIL because controller still expects `relationType/currentRelationType`.

- [ ] **Step 3: Rewrite controller and README examples**

```java
return lightRag.createRelation(workspaceId, new CreateRelationRequest(
    payload.sourceEntityName(),
    payload.targetEntityName(),
    payload.keywords(),
    payload.description(),
    payload.weight() == null ? 1.0d : payload.weight(),
    payload.sourceId(),
    payload.filePath()
));
```

Replace README samples so relation examples show `keywords` and endpoint-pair updates/deletes only.

- [ ] **Step 4: Bump the next minor snapshot**

```properties
projectVersion=0.17.0-SNAPSHOT
```

- [ ] **Step 5: Re-run controller tests**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests "io.github.lightrag.demo.GraphControllerTest"`

Expected: PASS

- [ ] **Step 6: Commit docs/demo/version updates**

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/GraphController.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/GraphControllerTest.java \
        README.md gradle.properties
git commit -m "feat: update relation demo and version"
```

### Task 6: Full verification and clean-break release checks

**Files:**
- Modify as needed based on failures from prior tasks
- Test: existing suites touched by relation semantics

- [ ] **Step 1: Run the core relation-focused JVM suites**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.RelationRequestContractTest" --tests "io.github.lightrag.api.LightRagWorkspaceTest" --tests "io.github.lightrag.E2ELightRagTest" --tests "io.github.lightrag.storage.postgres.PostgresGraphStoreTest" --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest" --tests "io.github.lightrag.storage.milvus.MilvusSdkClientAdapterTest"`

Expected: PASS

- [ ] **Step 2: Run provider-level integration coverage**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest"`

Expected: PASS

- [ ] **Step 3: Run the demo tests**

Run: `./gradlew :lightrag-spring-boot-demo:test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Inspect repository diff and summarize clean-break changes**

Run: `git status --short`

Expected: only intentional tracked changes for the new relation model, no leftover `EditRelationRequest`, no `relationType` references in core relation paths.

- [ ] **Step 5: Commit final fixes after verification**

```bash
git add lightrag-core lightrag-spring-boot-demo README.md gradle.properties
git commit -m "feat: finish upstream relation model alignment"
```

- [ ] **Step 6: Prepare release notes summary**

```text
- breaking relation API: `relationType` removed
- relation identity now uses canonical endpoint pairs
- relation store schemas rebuilt around `keywords`
- Milvus relation IDs now use short hash keys
- repository version advanced to 0.17.0-SNAPSHOT
```

## Self-Review

### Spec coverage

- relation semantics and identity: Task 1 and Task 2
- PostgreSQL/MySQL/Neo4j/Milvus alignment: Task 3 and Task 4
- API redesign: Task 1, Task 2, and Task 5
- clean-break upgrade contract: Task 5 docs plus Task 6 release notes
- version bump to next minor snapshot: Task 5

No spec section is left without an implementation task.

### Placeholder scan

- no `TODO`/`TBD`
- no vague "add validation" steps without code or commands
- each task includes concrete files, commands, and expected outcomes

### Type consistency

- `keywords` replaces `relationType` in all new API payloads
- `relation_id` is the short technical ID everywhere
- `src_id`/`tgt_id` are the canonical endpoint fields across stores

Plan complete and saved to `docs/superpowers/plans/2026-04-22-java-lightrag-upstream-relation-model-alignment.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration

2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Because you already asked me to start and did not request subagents, I will use **Inline Execution** in this session.
