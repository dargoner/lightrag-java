package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryChunkStore;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.storage.memory.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiWorkspaceQueryStorageProviderTest {

    @Test
    void servesTheGivenWorkspaceSetStoresForTheQueryPath() {
        var delegate = InMemoryStorageProvider.create();
        var chunkStore = new InMemoryChunkStore();
        var graphStore = new InMemoryGraphStore();
        var vectorStore = new InMemoryVectorStore();

        var provider = new MultiWorkspaceQueryStorageProvider(
            delegate,
            chunkStore,
            graphStore,
            vectorStore
        );

        assertThat(provider.chunkStore()).isSameAs(chunkStore);
        assertThat(provider.graphStore()).isSameAs(graphStore);
        assertThat(provider.vectorStore()).isSameAs(vectorStore);
    }

    @Test
    void delegatesTheRemainingStoresToTheDelegateAndNeutralizesTheLlmCache() {
        var delegate = InMemoryStorageProvider.create();
        var provider = new MultiWorkspaceQueryStorageProvider(
            delegate,
            new InMemoryChunkStore(),
            new InMemoryGraphStore(),
            new InMemoryVectorStore()
        );

        assertThat(provider.documentStore()).isSameAs(delegate.documentStore());
        assertThat(provider.documentStatusStore()).isSameAs(delegate.documentStatusStore());
        assertThat(provider.taskStore()).isSameAs(delegate.taskStore());
        assertThat(provider.taskStageStore()).isSameAs(delegate.taskStageStore());
        assertThat(provider.taskDocumentStore()).isSameAs(delegate.taskDocumentStore());
        assertThat(provider.snapshotStore()).isSameAs(delegate.snapshotStore());
        assertThat(provider.documentGraphSnapshotStore())
            .isSameAs(delegate.documentGraphSnapshotStore());
        assertThat(provider.documentGraphJournalStore())
            .isSameAs(delegate.documentGraphJournalStore());
        assertThat(provider.embeddingSpaceStore()).isSameAs(delegate.embeddingSpaceStore());

        assertThat(provider.llmCacheStore()).isInstanceOf(NoopLlmCacheStore.class);
        assertThat(provider.llmCacheStore()).isNotSameAs(delegate.llmCacheStore());
        provider.llmCacheStore().save(new LlmCacheStore.CacheRecord("cache-1", "payload"));
        assertThat(provider.llmCacheStore().load("cache-1")).isEmpty();
        assertThat(provider.llmCacheStore().contains("cache-1")).isFalse();
    }

    @Test
    void rejectsWritesAndRestoresForTheReadOnlyQuerySurface() {
        var provider = new MultiWorkspaceQueryStorageProvider(
            InMemoryStorageProvider.create(),
            new InMemoryChunkStore(),
            new InMemoryGraphStore(),
            new InMemoryVectorStore()
        );

        assertThatThrownBy(() -> provider.writeAtomically(storage -> null))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.restore(null))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("read-only");
    }
}
