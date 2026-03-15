package io.github.lightragjava.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void writeAtomicallyRestoresAllStoresWhenOperationFails() {
        var provider = InMemoryStorageProvider.create();
        var originalDocument = new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true"));
        var originalChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        var originalEntity = new GraphStore.EntityRecord(
            "entity-0",
            "Seed",
            "seed",
            "Seed entity",
            List.of("S"),
            List.of("doc-0:0")
        );
        var originalRelation = new GraphStore.RelationRecord(
            "relation-0",
            "entity-0",
            "entity-0",
            "self",
            "Seed relation",
            1.0d,
            List.of("doc-0:0")
        );
        var originalVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d));

        provider.documentStore().save(originalDocument);
        provider.chunkStore().save(originalChunk);
        provider.graphStore().saveEntity(originalEntity);
        provider.graphStore().saveRelation(originalRelation);
        provider.vectorStore().saveAll("chunks", List.of(originalVector));

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
            storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
            storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                "entity-1",
                "Incoming",
                "seed",
                "Incoming entity",
                List.of(),
                List.of("doc-1:0")
            ));
            storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-0",
                "links_to",
                "Incoming relation",
                0.5d,
                List.of("doc-1:0")
            ));
            storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d))));
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(provider.documentStore().list()).containsExactly(originalDocument);
        assertThat(provider.chunkStore().list()).containsExactly(originalChunk);
        assertThat(provider.graphStore().allEntities()).containsExactly(originalEntity);
        assertThat(provider.graphStore().allRelations()).containsExactly(originalRelation);
        assertThat(provider.vectorStore().list("chunks")).containsExactly(originalVector);
    }
}
