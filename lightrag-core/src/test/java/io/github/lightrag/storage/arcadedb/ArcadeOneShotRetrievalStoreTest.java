package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeOneShotRetrievalStoreTest {
    @Test
    void retrievesLocalGraphAndChunksFromEntityMatches() {
        var store = new ArcadeOneShotRetrievalStore(new FakeArcadeClient(), "default");

        var result = store.retrieveLocal(List.of(new VectorStore.VectorMatch("alice", 0.9d)));

        assertThat(result.entities()).extracting(entity -> entity.entity().id())
            .containsExactly("alice", "bob");
        assertThat(result.relations()).extracting(relation -> relation.relation().id())
            .containsExactly("rel-1");
        assertThat(result.chunks()).extracting(chunk -> chunk.chunk().id())
            .containsExactly("chunk-1");
    }

    @Test
    void retrievesGlobalGraphAndChunksFromRelationMatches() {
        var store = new ArcadeOneShotRetrievalStore(new FakeArcadeClient(), "default");

        var result = store.retrieveGlobal(List.of(new VectorStore.VectorMatch("rel-1", 0.8d)));

        assertThat(result.relations()).extracting(relation -> relation.relation().id())
            .containsExactly("rel-1");
        assertThat(result.entities()).extracting(entity -> entity.entity().id())
            .containsExactly("alice", "bob");
        assertThat(result.chunks()).extracting(chunk -> chunk.chunk().id())
            .containsExactly("chunk-1");
    }

    @Test
    void retrievesDirectChunksFromChunkMatches() {
        var store = new ArcadeOneShotRetrievalStore(new FakeArcadeClient(), "default");

        var result = store.retrieveChunks(List.of(new VectorStore.VectorMatch("chunk-1", 0.7d)));

        assertThat(result.chunks()).extracting(chunk -> chunk.chunk().id())
            .containsExactly("chunk-1");
        assertThat(result.chunks()).extracting(chunk -> chunk.score())
            .containsExactly(0.7d);
    }

    @Test
    void retrievesMixGraphAndDirectChunksTogether() {
        var store = new ArcadeOneShotRetrievalStore(new FakeArcadeClient(), "default");

        var result = store.retrieveMix(
            List.of(new VectorStore.VectorMatch("alice", 0.9d)),
            List.of(new VectorStore.VectorMatch("rel-1", 0.8d)),
            List.of(new VectorStore.VectorMatch("chunk-2", 0.7d))
        );

        assertThat(result.entities()).extracting(entity -> entity.entity().id())
            .containsExactly("alice", "bob");
        assertThat(result.relations()).extracting(relation -> relation.relation().id())
            .containsExactly("rel-1");
        assertThat(result.graphChunks()).extracting(chunk -> chunk.chunk().id())
            .containsExactly("chunk-1");
        assertThat(result.directChunks()).extracting(chunk -> chunk.chunk().id())
            .containsExactly("chunk-2");
    }

    private static final class FakeArcadeClient extends ArcadeDbClient {
        private FakeArcadeClient() {
            super(ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build());
        }

        @Override
        public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            if (sql.contains("FROM Relation") && sql.contains("srcId IN")) {
                return relations();
            }
            if (sql.contains("FROM Relation") && sql.contains("id IN")) {
                return relations();
            }
            if (sql.contains("FROM Entity")) {
                return entities(((List<?>) params.get("ids")).stream().map(Object::toString).toList());
            }
            if (sql.contains("FROM Chunk")) {
                return chunks(((List<?>) params.get("ids")).stream().map(Object::toString).toList());
            }
            return List.of();
        }

        private static List<Map<String, Object>> entities(List<String> ids) {
            return ids.stream()
                .filter(id -> id.equals("alice") || id.equals("bob"))
                .map(id -> Map.<String, Object>of(
                    "id", id,
                    "name", id,
                    "type", "person",
                    "description", id + " description",
                    "aliases", "[]",
                    "sourceChunkIds", "[\"chunk-1\"]"
                ))
                .toList();
        }

        private static List<Map<String, Object>> relations() {
            return List.of(Map.of(
                "id", "rel-1",
                "srcId", "alice",
                "tgtId", "bob",
                "keywords", "works_with",
                "description", "Alice works with Bob",
                "weight", 1.0d,
                "sourceId", "chunk-1",
                "filePath", ""
            ));
        }

        private static List<Map<String, Object>> chunks(List<String> ids) {
            return ids.stream()
                .filter(id -> id.equals("chunk-1") || id.equals("chunk-2"))
                .map(id -> Map.<String, Object>of(
                    "id", id,
                    "documentId", id.equals("chunk-1") ? "doc-1" : "doc-2",
                    "text", id.equals("chunk-1") ? "Alice works with Bob" : "Direct chunk",
                    "tokenCount", id.equals("chunk-1") ? 4 : 2,
                    "chunkOrder", 0,
                    "metadata", "{}"
                ))
                .toList();
        }
    }
}
