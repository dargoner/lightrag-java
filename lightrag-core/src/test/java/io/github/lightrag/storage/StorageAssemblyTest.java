package io.github.lightrag.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageAssemblyTest {
    @Test
    void buildsAtomicStorageProviderFromInjectedAdapters() {
        var relational = new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter();
        var graph = new StorageAssemblyTestDoubles.FakeGraphStorageAdapter();
        var vector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();

        var provider = StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(vector)
            .build()
            .toStorageProvider();

        assertThat(provider).isInstanceOf(AtomicStorageProvider.class);
        assertThat(provider.documentStore()).isSameAs(relational.documentStore());
        assertThat(provider.chunkStore()).isSameAs(relational.chunkStore());
        assertThat(provider.documentStatusStore()).isSameAs(relational.documentStatusStore());
        assertThat(provider.graphStore()).isSameAs(graph.graphStore());
        assertThat(provider.vectorStore()).isSameAs(vector.vectorStore());
    }
}
