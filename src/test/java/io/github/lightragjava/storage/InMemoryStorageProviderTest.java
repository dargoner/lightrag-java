package io.github.lightragjava.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStorageProviderTest {
    @Test
    void providerExposesConsistentStoreInstances() {
        var provider = InMemoryStorageProvider.create();

        assertThat(provider.documentStore()).isSameAs(provider.documentStore());
        assertThat(provider.chunkStore()).isSameAs(provider.chunkStore());
        assertThat(provider.graphStore()).isSameAs(provider.graphStore());
        assertThat(provider.vectorStore()).isSameAs(provider.vectorStore());
        assertThat(provider.snapshotStore()).isSameAs(provider.snapshotStore());
    }
}
