package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagWorkspaceTest {
    @Test
    void oneLightRagInstanceRoutesDifferentCallsToDifferentWorkspaceProviders() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new WorkspaceEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.ingest("alpha", List.of(new Document("doc-alpha", "Alpha", "alpha token", Map.of())));
        rag.ingest("beta", List.of(new Document("doc-beta", "Beta", "beta token", Map.of())));

        assertThat(workspaceStorageProvider.provider("alpha").documentStore().load("doc-alpha")).isPresent();
        assertThat(workspaceStorageProvider.provider("alpha").documentStore().load("doc-beta")).isNotPresent();
        assertThat(workspaceStorageProvider.provider("beta").documentStore().load("doc-beta")).isPresent();
        assertThat(workspaceStorageProvider.provider("beta").documentStore().load("doc-alpha")).isNotPresent();

        var alphaResult = rag.query("alpha", QueryRequest.builder()
            .query("alpha")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .onlyNeedContext(true)
            .build());
        var betaResult = rag.query("beta", QueryRequest.builder()
            .query("beta")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .onlyNeedContext(true)
            .build());

        assertThat(alphaResult.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-alpha:0");
        assertThat(betaResult.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-beta:0");
    }

    @Test
    void structuredQueryRoutesDifferentCallsToDifferentWorkspaceProviders() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new WorkspaceEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.ingest("alpha", List.of(new Document("doc-alpha", "Alpha", "alpha token", Map.of())));
        rag.ingest("beta", List.of(new Document("doc-beta", "Beta", "beta token", Map.of())));

        var alphaResult = rag.queryStructured("alpha", QueryRequest.builder()
            .query("alpha")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .onlyNeedContext(true)
            .build());
        var betaResult = rag.queryStructured("beta", QueryRequest.builder()
            .query("beta")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .onlyNeedContext(true)
            .build());

        assertThat(alphaResult.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-alpha:0");
        assertThat(alphaResult.chunks())
            .extracting(StructuredQueryChunk::id)
            .containsExactly("doc-alpha:0");
        assertThat(betaResult.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-beta:0");
        assertThat(betaResult.chunks())
            .extracting(StructuredQueryChunk::id)
            .containsExactly("doc-beta:0");
    }

    @Test
    void saveSnapshotAndRestoreSnapshotOperateOnTheTargetWorkspaceOnly() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new WorkspaceEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(false)
            .build();
        var snapshotPath = Path.of("snapshots", "alpha.snapshot.json");

        rag.ingest("alpha", List.of(new Document("doc-alpha-1", "Alpha", "alpha token", Map.of())));
        rag.ingest("beta", List.of(new Document("doc-beta-1", "Beta", "beta token", Map.of())));
        rag.saveSnapshot("alpha", snapshotPath);
        rag.ingest("alpha", List.of(new Document("doc-alpha-2", "Alpha 2", "alpha extra", Map.of())));

        rag.restoreSnapshot("alpha", snapshotPath);

        assertThat(workspaceStorageProvider.provider("alpha").documentStore().load("doc-alpha-1")).isPresent();
        assertThat(workspaceStorageProvider.provider("alpha").documentStore().load("doc-alpha-2")).isNotPresent();
        assertThat(workspaceStorageProvider.provider("beta").documentStore().load("doc-beta-1")).isPresent();
    }

    @Test
    void repeatedWorkspaceResolutionReusesTheSameLogicalProviderInstance() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new WorkspaceEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.listDocumentStatuses("alpha");
        rag.listDocumentStatuses("alpha");
        rag.listDocumentStatuses("beta");

        assertThat(workspaceStorageProvider.lookupCount("alpha")).isEqualTo(2);
        assertThat(workspaceStorageProvider.lookupCount("beta")).isEqualTo(1);
        assertThat(workspaceStorageProvider.createdCount("alpha")).isEqualTo(1);
        assertThat(workspaceStorageProvider.createdCount("beta")).isEqualTo(1);
    }

    private static final class TestWorkspaceStorageProvider implements WorkspaceStorageProvider {
        private final Map<String, AtomicStorageProvider> providers = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> lookupCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> createdCounts = new ConcurrentHashMap<>();

        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            lookupCounts.computeIfAbsent(scope.workspaceId(), ignored -> new AtomicInteger()).incrementAndGet();
            return providers.computeIfAbsent(scope.workspaceId(), workspaceId -> {
                createdCounts.computeIfAbsent(workspaceId, ignored -> new AtomicInteger()).incrementAndGet();
                return InMemoryStorageProvider.create();
            });
        }

        @Override
        public void close() {
        }

        private AtomicStorageProvider provider(String workspaceId) {
            return providers.get(workspaceId);
        }

        private int lookupCount(String workspaceId) {
            return lookupCounts.getOrDefault(workspaceId, new AtomicInteger()).get();
        }

        private int createdCount(String workspaceId) {
            return createdCounts.getOrDefault(workspaceId, new AtomicInteger()).get();
        }
    }

    private static final class NoOpChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class WorkspaceEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(WorkspaceEmbeddingModel::embed)
                .toList();
        }

        private static List<Double> embed(String text) {
            var normalized = text.toLowerCase();
            return List.of(
                normalized.contains("alpha") ? 1.0d : 0.0d,
                normalized.contains("beta") ? 1.0d : 0.0d,
                normalized.contains("extra") ? 1.0d : 0.0d
            );
        }
    }
}
