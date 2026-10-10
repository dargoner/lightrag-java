package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.MultiWorkspaceQueryStorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagWorkspaceSetQueryTest {

    @Test
    void queryForWorkspacesRunsASinglePipelineAcrossTheWorkspaceSet() {
        var alpha = InMemoryStorageProvider.create();
        var beta = InMemoryStorageProvider.create();
        var workspaceStorageProvider = new SetWorkspaceStorageProvider(alpha, beta);
        var rag = newRag(workspaceStorageProvider);

        rag.ingest("alpha", List.of(new Document("doc-alpha", "Alpha", "alpha token", Map.of())));
        rag.ingest("beta", List.of(new Document("doc-beta", "Beta", "beta token", Map.of())));
        var singleLookupsBeforeQuery = workspaceStorageProvider.singleWorkspaceLookups.size();

        var result = rag.queryForWorkspaces(List.of("alpha", "beta"), QueryRequest.builder()
            .query("alpha beta")
            .mode(QueryMode.NAIVE)
            .chunkTopK(10)
            .onlyNeedContext(true)
            .build());

        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactlyInAnyOrder("doc-alpha:0", "doc-beta:0");
        assertThat(workspaceStorageProvider.workspaceSetLookups).isEqualTo(1);
        assertThat(workspaceStorageProvider.singleWorkspaceLookups).hasSize(singleLookupsBeforeQuery);
    }

    @Test
    void queryForWorkspacesRejectsEmptyWorkspaceList() {
        var rag = newRag(new SetWorkspaceStorageProvider(
            InMemoryStorageProvider.create(),
            InMemoryStorageProvider.create()
        ));

        assertThatThrownBy(() -> rag.queryForWorkspaces(List.of(), QueryRequest.builder()
            .query("alpha")
            .mode(QueryMode.NAIVE)
            .onlyNeedContext(true)
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspaceIds must not be empty");
    }

    @Test
    void queryForWorkspacesRejectsBlankWorkspaceIds() {
        var rag = newRag(new SetWorkspaceStorageProvider(
            InMemoryStorageProvider.create(),
            InMemoryStorageProvider.create()
        ));
        var request = QueryRequest.builder()
            .query("alpha")
            .mode(QueryMode.NAIVE)
            .onlyNeedContext(true)
            .build();

        assertThatThrownBy(() -> rag.queryForWorkspaces(Arrays.asList("alpha", null), request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspaceIds must not contain blank ids");
        assertThatThrownBy(() -> rag.queryForWorkspaces(List.of("alpha", "   "), request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspaceIds must not contain blank ids");
    }

    @Test
    void queryForWorkspacesTrimsAndDeduplicatesWorkspaceIds() {
        var alpha = InMemoryStorageProvider.create();
        var beta = InMemoryStorageProvider.create();
        var workspaceStorageProvider = new SetWorkspaceStorageProvider(alpha, beta);
        var rag = newRag(workspaceStorageProvider);
        rag.ingest("alpha", List.of(new Document("doc-alpha", "Alpha", "alpha token", Map.of())));
        rag.ingest("beta", List.of(new Document("doc-beta", "Beta", "beta token", Map.of())));

        var result = rag.queryForWorkspaces(List.of("alpha", " beta ", "alpha"), QueryRequest.builder()
            .query("alpha beta")
            .mode(QueryMode.NAIVE)
            .chunkTopK(10)
            .onlyNeedContext(true)
            .build());

        // Trim and first-win dedup produce the canonical ["alpha", "beta"] set, which the
        // storage provider serves as its workspace-set lookup; without either step the scope
        // list would not match the canonical set.
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactlyInAnyOrder("doc-alpha:0", "doc-beta:0");
        assertThat(workspaceStorageProvider.workspaceSetLookups).isEqualTo(1);
    }

    @Test
    void defaultProviderServesSingleWorkspaceListAndRejectsMultiWorkspaceList() {
        var rag = newRag(new SingleWorkspaceOnlyProvider());
        rag.ingest("alpha", List.of(new Document("doc-alpha", "Alpha", "alpha token", Map.of())));

        var result = rag.queryForWorkspaces(List.of("alpha"), QueryRequest.builder()
            .query("alpha")
            .mode(QueryMode.NAIVE)
            .chunkTopK(5)
            .onlyNeedContext(true)
            .build());
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-alpha:0");

        assertThatThrownBy(() -> rag.queryForWorkspaces(
            List.of("alpha", "beta"),
            QueryRequest.builder()
                .query("alpha")
                .mode(QueryMode.NAIVE)
                .onlyNeedContext(true)
                .build()
        ))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("workspace-set queries are not supported by");
    }

    private static LightRag newRag(WorkspaceStorageProvider workspaceStorageProvider) {
        return LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new KeywordEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(false)
            .build();
    }

    /**
     * Serves single-workspace calls from the per-workspace stores and multi-workspace calls through
     * a {@link MultiWorkspaceQueryStorageProvider} whose read surface unions both workspaces'
     * in-memory stores, mirroring what the platform does with its backend-specific set stores.
     */
    private static final class SetWorkspaceStorageProvider implements WorkspaceStorageProvider {
        private final AtomicStorageProvider alpha;
        private final AtomicStorageProvider beta;
        private final List<String> singleWorkspaceLookups = new ArrayList<>();
        private int workspaceSetLookups;

        private SetWorkspaceStorageProvider(AtomicStorageProvider alpha, AtomicStorageProvider beta) {
            this.alpha = alpha;
            this.beta = beta;
        }

        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            singleWorkspaceLookups.add(scope.workspaceId());
            return switch (scope.workspaceId()) {
                case "alpha" -> alpha;
                case "beta" -> beta;
                default -> InMemoryStorageProvider.create();
            };
        }

        @Override
        public AtomicStorageProvider forWorkspaces(List<WorkspaceScope> scopes) {
            var workspaceIds = scopes.stream().map(WorkspaceScope::workspaceId).toList();
            if (workspaceIds.equals(List.of("alpha", "beta"))) {
                workspaceSetLookups++;
                return new MultiWorkspaceQueryStorageProvider(
                    alpha,
                    new UnionChunkStore(List.of(alpha.chunkStore(), beta.chunkStore())),
                    alpha.graphStore(),
                    new UnionVectorStore(List.of(alpha.vectorStore(), beta.vectorStore()))
                );
            }
            return WorkspaceStorageProvider.super.forWorkspaces(scopes);
        }

        @Override
        public void close() {
        }
    }

    private static final class SingleWorkspaceOnlyProvider implements WorkspaceStorageProvider {
        private final Map<String, AtomicStorageProvider> providers = new ConcurrentHashMap<>();

        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            return providers.computeIfAbsent(
                scope.workspaceId(),
                ignored -> InMemoryStorageProvider.create()
            );
        }

        @Override
        public void close() {
        }
    }

    private static final class UnionChunkStore implements ChunkStore {
        private final List<ChunkStore> stores;

        private UnionChunkStore(List<ChunkStore> stores) {
            this.stores = List.copyOf(stores);
        }

        @Override
        public void save(ChunkRecord chunk) {
            throw new UnsupportedOperationException("union chunk store is read-only");
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            for (var store : stores) {
                var loaded = store.load(chunkId);
                if (loaded.isPresent()) {
                    return loaded;
                }
            }
            return Optional.empty();
        }

        @Override
        public List<ChunkRecord> list() {
            return stores.stream().flatMap(store -> store.list().stream()).toList();
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return stores.stream()
                .flatMap(store -> store.listByDocument(documentId).stream())
                .toList();
        }
    }

    private static final class UnionVectorStore implements VectorStore {
        private final List<VectorStore> stores;

        private UnionVectorStore(List<VectorStore> stores) {
            this.stores = List.copyOf(stores);
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            throw new UnsupportedOperationException("union vector store is read-only");
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            var merged = new LinkedHashMap<String, VectorMatch>();
            stores.stream()
                .flatMap(store -> store.search(namespace, queryVector, topK).stream())
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed())
                .forEach(match -> merged.putIfAbsent(match.id(), match));
            return merged.values().stream().limit(topK).toList();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return stores.stream().flatMap(store -> store.list(namespace).stream()).toList();
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

    private static final class KeywordEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(KeywordEmbeddingModel::embed).toList();
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
