package io.github.lightrag.storage;

import io.github.lightrag.storage.neo4j.Neo4jGraphStore;
import io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStore;
import io.github.lightrag.storage.postgres.PostgresGraphStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior parity cannot observe a missing wrapper delegation: falling back to the interface default
 * still returns the correct view, just without the backend down-push. These structural checks pin
 * the declared overrides at both native adapters and every wrapper in front of them.
 */
class GraphViewDelegationTest {
    @Test
    void bothNativeAdaptersDeclareTheKnowledgeGraphOverride() {
        assertDeclaresKnowledgeGraphOverride(PostgresGraphStore.class);
        assertDeclaresKnowledgeGraphOverride(WorkspaceScopedNeo4jGraphStore.class);
    }

    @Test
    void everyWrapperForwardsTheKnowledgeGraphOverride() throws ClassNotFoundException {
        var wrappers = List.of(
            Neo4jGraphStore.class,
            Class.forName("io.github.lightrag.storage.postgres.PostgresStorageProvider$LockedGraphStore"),
            Class.forName("io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProvider$LockedGraphStore"),
            Class.forName("io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProvider$MirroringGraphStore"),
            Class.forName("io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProvider$MirroringGraphStore"),
            Class.forName("io.github.lightrag.storage.neo4j.Neo4jGraphStorageAdapter$WorkspaceStoreProjection")
        );

        for (var wrapper : wrappers) {
            assertDeclaresKnowledgeGraphOverride(wrapper);
        }
    }

    private static void assertDeclaresKnowledgeGraphOverride(Class<?> type) {
        assertThat(type.getDeclaredMethods())
            .as("%s declares getKnowledgeGraph(String, int, int)", type.getName())
            .anyMatch(method ->
                method.getName().equals("getKnowledgeGraph")
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class, int.class, int.class}));
    }
}
