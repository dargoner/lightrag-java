# Java LightRAG Resumable Ingest Checkpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor normal ingest so base document data, chunk vectors, and extraction snapshots become durable before final graph materialization, allowing failed finalization to be resumed through the existing graph materialization pipeline.

**Architecture:** The implementation keeps the current public task-stage enums but splits the late ingest commit into three durable checkpoints: base rows, extraction snapshot state, and final graph materialization. Initial graph journals become honest "not materialized yet" records, while final success and failure truth is only written during the last materialization phase.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, in-memory storage provider, task/event projection in `lightrag-core`

---

## File Structure

### Core ingest implementation

- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`

### Focused regression tests

- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java`

### Optional follow-up verification if task/event projection drifts

- Inspect only if needed: `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java`

---

### Task 1: Lock the new checkpoint semantics with failing tests

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java`

- [ ] **Step 1: Keep the resumable-ingest red tests focused on the new contract**

```java
@Test
void persistsDocumentChunksAndChunkVectorsBeforePrimaryExtractionStarts() {
    var storage = InMemoryStorageProvider.create();
    var listener = new IndexingProgressListener() {
        @Override
        public void onStageStarted(TaskStage stage, String message) {
            if (stage != TaskStage.PRIMARY_EXTRACTION) {
                return;
            }
            assertThat(storage.documentStore().load("doc-1")).isPresent();
            assertThat(storage.chunkStore().listByDocument("doc-1"))
                .extracting(ChunkStore.ChunkRecord::id)
                .containsExactly("doc-1:0");
            assertThat(storage.documentStatusStore().load("doc-1"))
                .contains(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSING, "", null));
            assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
                .extracting(VectorStore.VectorRecord::id)
                .containsExactly("doc-1:0");
        }
    };
    newPipeline(storage, listener).ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
}
```

```java
@Test
void preservesGraphSnapshotsWhenInitialMaterializationFailsSoDocumentCanBeResumed() {
    var storage = new FailingFirstGraphWriteStorageProvider();

    assertThatThrownBy(() -> newPipeline(storage, IndexingProgressListener.noop()).ingest(List.of(
        new Document("doc-1", "Title", "Alice works with Bob", Map.of())
    ))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic graph write failure");

    assertThat(storage.documentGraphSnapshotStore().loadDocument("doc-1")).isPresent();
    assertThat(storage.documentGraphSnapshotStore().listChunks("doc-1"))
        .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
        .containsExactly("doc-1:0");
}
```

- [ ] **Step 2: Add a red test for the new concurrent-failure contract**

Replace the old "no visible rows" expectation in `LightRagBuilderTest` with:

```java
assertThat(rag.getDocumentStatus(WORKSPACE, "doc-slow").status()).isEqualTo(DocumentStatus.FAILED);
assertThat(storageProvider.documentStore().contains("doc-slow")).isTrue();
assertThat(storageProvider.chunkStore().listByDocument("doc-slow")).isNotEmpty();
assertThat(storageProvider.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
    .extracting(VectorStore.VectorRecord::id)
    .contains("doc-slow:0");
assertThat(storageProvider.documentGraphSnapshotStore().loadDocument("doc-slow")).isPresent();
assertThat(storageProvider.graphStore().allEntities()).isEmpty();
```

This intentionally encodes the new rule: checkpoint data may survive, but formal graph materialization must not look successful.

- [ ] **Step 3: Add a red task/event test for snapshot-ready semantics**

In `LightRagTaskApiTest`, add one focused assertion that `DOCUMENT_GRAPH_READY` happens before `DOCUMENT_COMMITTED` and does not imply `PROCESSED` by itself:

```java
var documentEvents = events.stream()
    .filter(event -> "doc-1".equals(event.documentId()))
    .toList();

assertThat(indexOfDocumentEvent(documentEvents, TaskEventType.DOCUMENT_GRAPH_READY))
    .isLessThan(indexOfDocumentEvent(documentEvents, TaskEventType.DOCUMENT_COMMITTED));
```

Add the helper near the existing chunk-event helper:

```java
private static int indexOfDocumentEvent(List<TaskEvent> events, TaskEventType eventType) {
    for (int index = 0; index < events.size(); index++) {
        if (eventType == events.get(index).eventType()) {
            return index;
        }
    }
    return -1;
}
```

- [ ] **Step 4: Run the focused red tests**

Run:

```bash
/bin/bash -lc 'cd /home/dargoner/work/lightrag-java && GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineResumableIngestTest" --tests "io.github.lightrag.api.LightRagBuilderTest.doesNotCommitOtherDocumentsAfterConcurrentFailure" --tests "io.github.lightrag.api.LightRagTaskApiTest.submitIngestPublishesDocumentLifecycleEventsToRegisteredListener"'
```

Expected:

- `IndexingPipelineResumableIngestTest` fails because base rows and snapshots are still persisted too late
- `LightRagBuilderTest` fails because it still asserts `documentStore().contains("doc-slow") == false`
- `LightRagTaskApiTest` may still pass or remain neutral; keep the new ordering assertion in place for later regression protection

- [ ] **Step 5: Commit the red tests**

```bash
git add lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java
git commit -m "test: lock resumable ingest checkpoint semantics"
```

### Task 2: Split the late commit into durable base, snapshot, and finalization windows

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java`

- [ ] **Step 1: Add failing helper-level expectations in `IndexingPipeline` by replacing the monolithic commit path**

Replace the current `commitComputedIngest(...)` call sites in the sequential and concurrent ingest methods with the new flow:

```java
var computed = computeDocument(source);
persistBaseDocumentState(computed);
persistChunkVectorState(computed);
persistExtractionSnapshotState(computed);
commitFinalMaterialization(computed, false);
```

For pre-chunked ingest, preserve chunk event publication:

```java
commitFinalMaterialization(computed, true);
```

- [ ] **Step 2: Implement the base-row checkpoint helper**

Add this helper near the old commit method:

```java
private void persistBaseDocumentState(ComputedIngest computed) {
    synchronized (storageMutationMonitor) {
        storageProvider.writeAtomically(storage -> {
            documentIngestor.persist(computed.prepared(), storage);
            storage.documentStatusStore().save(processingStatus(computed.source().id()));
            return null;
        });
    }
}
```

- [ ] **Step 3: Implement the chunk-vector checkpoint helper**

```java
private void persistChunkVectorState(ComputedIngest computed) {
    synchronized (storageMutationMonitor) {
        storageProvider.writeAtomically(storage -> {
            saveChunkVectors(computed.prepared().chunks(), computed.chunkVectors(), storage.vectorStore());
            return null;
        });
    }
}
```

- [ ] **Step 4: Convert the old commit method into final-materialization only**

Rename the method and remove base writes that now happen earlier:

```java
private void commitFinalMaterialization(ComputedIngest computed, boolean publishChunkEvents) {
    progressListener.onStageStarted(TaskStage.COMMITTING, "materializing graph and final vectors");
    synchronized (storageMutationMonitor) {
        storageProvider.writeAtomically(storage -> {
            saveGraph(computed.graph().entities(), computed.graph().relations(), storage);
            saveEntityVectors(computed.graph().entities(), computed.entityVectors(), storage.vectorStore());
            saveRelationVectors(computed.graph().relations(), computed.relationVectors(), storage.vectorStore());
            finalizeDocumentGraphState(computed, storage);
            storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                computed.source().id(),
                DocumentStatus.PROCESSED,
                "processed %d chunks".formatted(computed.prepared().chunks().size()),
                null
            ));
            return null;
        });
    }
    progressListener.onStageSucceeded(TaskStage.COMMITTING, "materialized graph and committed final state");
    if (publishChunkEvents) {
        for (var chunk : computed.prepared().chunks()) {
            progressListener.onChunkSucceeded(computed.source().id(), chunk.id(), "chunk committed");
        }
    }
    progressListener.onDocumentCommitted(computed.source().id(), "document committed");
}
```

- [ ] **Step 5: Re-run the focused resumable-ingest tests**

Run:

```bash
/bin/bash -lc 'cd /home/dargoner/work/lightrag-java && GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineResumableIngestTest"'
```

Expected:

- the first test now passes because base rows and chunk vectors exist before `PRIMARY_EXTRACTION`
- the second test may still fail until initial snapshot journals become honest pre-materialization records

- [ ] **Step 6: Commit the checkpoint split**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java
git commit -m "feat: split ingest into durable checkpoints"
```

### Task 3: Make snapshot and journal state honest before final graph materialization

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: Replace the old mixed `persistDocumentGraphState(...)` helper with two explicit helpers**

Add an extraction-checkpoint helper:

```java
private void persistExtractionSnapshotState(ComputedIngest computed) {
    progressListener.onStageStarted(TaskStage.GRAPH_ASSEMBLY, "persisting extraction snapshots for " + computed.source().id());
    synchronized (storageMutationMonitor) {
        storageProvider.writeAtomically(storage -> {
            initializeDocumentGraphState(computed, storage);
            return null;
        });
    }
    progressListener.onDocumentGraphReady(
        computed.source().id(),
        computed.graph().entities().size(),
        computed.graph().relations().size(),
        "persisted extraction snapshots for " + computed.source().id()
    );
    progressListener.onStageSucceeded(TaskStage.GRAPH_ASSEMBLY, "persisted extraction snapshots for " + computed.source().id());
}
```

And a finalization helper:

```java
private void finalizeDocumentGraphState(
    ComputedIngest computed,
    AtomicStorageProvider.AtomicStorageView storage
) {
    var documentId = computed.source().id();
    var journalStore = storage.documentGraphJournalStore();
    var snapshotVersion = storage.documentGraphSnapshotStore().loadDocument(documentId)
        .map(DocumentGraphSnapshotStore.DocumentGraphSnapshot::version)
        .orElseThrow(() -> new IllegalStateException("missing graph snapshot for " + documentId));

    journalStore.appendDocument(successDocumentJournal(computed, snapshotVersion, Instant.now()));
    journalStore.appendChunks(documentId, successChunkJournals(documentId, snapshotVersion, computed.extractions(), Instant.now()));
}
```

- [ ] **Step 2: Write honest initial journal records**

Change the initial document journal from `MERGED` to `NOT_STARTED`:

```java
new DocumentGraphJournalStore.DocumentGraphJournal(
    documentId,
    snapshotVersion,
    GraphMaterializationStatus.NOT_STARTED,
    GraphMaterializationMode.AUTO,
    computed.graph().entities().size(),
    computed.graph().relations().size(),
    0,
    0,
    null,
    now,
    now,
    null
)
```

Change each initial chunk journal from `SUCCEEDED/MATERIALIZED` to `NOT_STARTED/NOT_MATERIALIZED`:

```java
new DocumentGraphJournalStore.ChunkGraphJournal(
    documentId,
    extraction.chunkId(),
    snapshotVersion,
    ChunkMergeStatus.NOT_STARTED,
    ChunkGraphStatus.NOT_MATERIALIZED,
    entityKeys,
    relationKeys,
    List.of(),
    List.of(),
    null,
    now,
    null
)
```

- [ ] **Step 3: Preserve snapshot data on finalization failure**

Wrap the finalization write path so the failure updates only status and error truth, not the already-durable checkpoints:

```java
try {
    commitFinalMaterialization(computed, publishChunkEvents);
} catch (RuntimeException | Error failure) {
    markDocumentFailed(computed.source().id(), failure);
    throw failure;
}
```

Do not delete document snapshots or journals inside the failure path.

- [ ] **Step 4: Re-run the resumable and concurrency tests**

Run:

```bash
/bin/bash -lc 'cd /home/dargoner/work/lightrag-java && GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineResumableIngestTest" --tests "io.github.lightrag.api.LightRagBuilderTest.doesNotCommitOtherDocumentsAfterConcurrentFailure"'
```

Expected:

- `IndexingPipelineResumableIngestTest` fully passes
- `LightRagBuilderTest` now passes with durable checkpoint rows plus `FAILED` status for the interrupted peer document

- [ ] **Step 5: Commit the honest journal and resume behavior**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineResumableIngestTest.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java
git commit -m "feat: persist resumable graph snapshots before finalization"
```

### Task 4: Align task-event tests and run the focused regression suite

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java`
- Inspect only if needed: `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java`

- [ ] **Step 1: Keep document lifecycle assertions but clarify ordering**

In `submitIngestPublishesDocumentLifecycleEventsToRegisteredListener`, preserve the existing event set and add the ordering assertion:

```java
assertThat(events.stream()
    .filter(event -> event.documentId() != null)
    .map(TaskEvent::eventType)
    .toList()).contains(
    TaskEventType.DOCUMENT_STARTED,
    TaskEventType.DOCUMENT_CHUNKED,
    TaskEventType.DOCUMENT_GRAPH_READY,
    TaskEventType.DOCUMENT_VECTORS_READY,
    TaskEventType.DOCUMENT_COMMITTED
);

var documentEvents = events.stream()
    .filter(event -> "doc-1".equals(event.documentId()))
    .toList();
assertThat(indexOfDocumentEvent(documentEvents, TaskEventType.DOCUMENT_GRAPH_READY))
    .isLessThan(indexOfDocumentEvent(documentEvents, TaskEventType.DOCUMENT_COMMITTED));
```

- [ ] **Step 2: Keep stage assertions stable while allowing the new interpretation**

Retain the stage-presence assertion:

```java
assertThat(task.stages())
    .extracting(TaskStageSnapshot::stage)
    .contains(
        TaskStage.PREPARING,
        TaskStage.CHUNKING,
        TaskStage.PRIMARY_EXTRACTION,
        TaskStage.GRAPH_ASSEMBLY,
        TaskStage.VECTOR_INDEXING,
        TaskStage.COMMITTING,
        TaskStage.COMPLETED
    );
```

Do not add a new enum in this task. The implementation should pass with the current stage list and the corrected stage messages.

- [ ] **Step 3: Run the focused regression suite**

Run:

```bash
/bin/bash -lc 'cd /home/dargoner/work/lightrag-java && GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineResumableIngestTest" --tests "io.github.lightrag.api.LightRagBuilderTest" --tests "io.github.lightrag.api.LightRagTaskApiTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false'
```

Expected:

- all resumable-ingest tests pass
- builder tests pass with the new checkpoint semantics
- task API tests pass without enum changes
- graph materialization tests continue to pass because the pipeline still treats snapshot-without-graph as resumable state

- [ ] **Step 4: If task-event projection drifts, fix projection code before re-running**

If `LightRagTaskApiTest` fails because task-document projection now reports the wrong status or ordering, inspect `TaskExecutionService` and keep this logic:

```java
public void onDocumentCommitted(String documentId, String message) {
    upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
        taskId,
        documentId,
        DocumentStatus.PROCESSED,
        existing.chunkCount(),
        existing.entityCount(),
        existing.relationCount(),
        existing.chunkVectorCount(),
        existing.entityVectorCount(),
        existing.relationVectorCount(),
        null
    ));
}
```

Only `DOCUMENT_COMMITTED` should project `PROCESSED`.

- [ ] **Step 5: Commit the task/event alignment**

```bash
git add lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java
git commit -m "test: align task events with resumable ingest checkpoints"
```

