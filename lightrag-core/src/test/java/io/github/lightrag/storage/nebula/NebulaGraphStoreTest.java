package io.github.lightrag.storage.nebula;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.InvalidConfigException;
import com.vesoft.nebula.client.graph.exception.InvalidValueException;
import com.vesoft.nebula.client.graph.exception.NotValidConnectionException;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import io.github.lightrag.support.GraphViewParity;
import io.github.lightrag.support.NebulaTestContainers;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NebulaGraphStoreTest {
    private static final String SPACE = "lightrag_e2e";

    private static final NebulaTestContainers.Cluster CLUSTER = NebulaTestContainers.shared();

    @Test
    void createsTheTagAndEdgeSchemaAndIndexesOnceAndReplaysTheBootstrapSafely() {
        var workspace = newWorkspaceId();
        try (var store = newStore(workspace)) {
            store.saveEntity(entity("entity-1", "Alice"));

            // Replaying the bootstrap (a second store over the same space) must be a no-op.
            try (var replay = newStore(workspace)) {
                assertThat(replay.loadEntity("entity-1")).isPresent();
            }
        }

        withRawSession(session -> {
            assertThat(columnValues(execute(session, "SHOW TAG INDEXES;"), "Index Name"))
                .containsExactly("idx_entity_workspace_id");
            assertThat(columnValues(execute(session, "SHOW EDGE INDEXES;"), "Index Name"))
                .containsExactlyInAnyOrder(
                    "idx_directed_workspace_id",
                    "idx_directed_workspace_relation_id"
                );
            return null;
        });
    }

    @Test
    void roundTripsEntitiesAndRelationsWithSpecialCharacters() {
        try (var store = newStore()) {
            var alice = new EntityRecord(
                "entity-\"1\"\\🚀",
                "Alice \"Liddell\"",
                "person",
                "Line1\nLine2 中文 🎩 $AGE1$",
                List.of("A", "阿丽丝"),
                List.of("chunk-1", "chunk-2"),
                "/docs/alice.md<SEP>/docs/bob.md"
            );
            var bob = new EntityRecord("entity-2", "Bob", "person", "", List.of(), List.of());
            var relation = new RelationRecord(
                "relation-1",
                alice.id(),
                bob.id(),
                "knows",
                "Alice knows Bob\n(中文备注)",
                0.9d,
                "chunk-1<SEP>chunk-2",
                "/docs/alice.md"
            );

            store.saveEntity(alice);
            store.saveEntity(bob);
            store.saveRelation(relation);

            assertThat(store.loadEntity(alice.id())).contains(alice);
            assertThat(store.loadEntity(bob.id())).contains(bob);
            assertThat(store.loadRelation("relation-1")).contains(relation);
        }
    }

    @Test
    void upsertingAnEntityReplacesStoredPropertiesWithoutDuplicates() {
        try (var store = newStore()) {
            store.saveEntity(new EntityRecord("entity-1", "Alice", "person", "old", List.of("A"), List.of("chunk-1")));
            var updated = new EntityRecord(
                "entity-1",
                "Alice Smith",
                "researcher",
                "new",
                List.of("A", "Alice"),
                List.of("chunk-2")
            );

            store.saveEntity(updated);

            assertThat(store.loadEntity("entity-1")).contains(updated);
            assertThat(store.allEntities()).containsExactly(updated);
            assertThat(store.labels()).containsExactly("entity-1");
        }
    }

    @Test
    void upsertingARelationReplacesTheEdgeBetweenTheSameEndpoints() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2");
            store.saveRelation(new RelationRecord("relation-1", "e1", "e2", "knows", "first", 0.9d, List.of("chunk-1")));
            var updated = new RelationRecord("relation-1", "e1", "e2", "knows", "second", 0.4d, List.of("chunk-2"));

            store.saveRelation(updated);

            assertThat(store.allRelations()).containsExactly(updated);
            assertThat(store.loadRelation("relation-1")).contains(updated);
            assertThat(store.findRelations("e1")).containsExactly(updated);

            // Upstream keeps at most one edge per endpoint pair: a save whose endpoints match an
            // existing edge deletes that edge even when the incoming relation id differs.
            var samePairDifferentId = new RelationRecord("relation-2", "e2", "e1", "knows", "reverse", 0.7d, List.of("chunk-3"));

            store.saveRelation(samePairDifferentId);

            assertThat(store.allRelations()).containsExactly(samePairDifferentId);
            assertThat(store.loadRelation("relation-1")).isEmpty();
            assertThat(store.findRelations("e1")).containsExactly(samePairDifferentId);
        }
    }

    @Test
    void selfLoopsAreStoredAndFoundExactlyOnce() {
        try (var store = newStore()) {
            saveEntities(store, "e1");
            var selfLoop = new RelationRecord("relation-self", "e1", "e1", "knows", "self", 0.5d, List.of("chunk-1"));

            store.saveRelation(selfLoop);

            assertThat(store.findRelations("e1")).containsExactly(selfLoop);
            assertThat(store.allRelations()).containsExactly(selfLoop);
            assertThat(store.loadRelation("relation-self")).contains(selfLoop);

            // Re-saving a self-loop replaces it instead of doubling it.
            store.saveRelation(selfLoop);

            assertThat(store.allRelations()).containsExactly(selfLoop);
            assertThat(store.deleteRelations(List.of("relation-self"))).isEqualTo(1);
        }
    }

    @Test
    void projectsMissingEndpointsAsPlaceholderVertices() {
        try (var store = newStore()) {
            saveEntities(store, "present");
            var relation = new RelationRecord("relation-1", "present", "missing", "knows", "d", 0.5d, List.of("chunk-1"));

            store.saveRelation(relation);

            // The relation is visible, but its placeholder endpoint reads as an absent entity.
            assertThat(store.loadRelation("relation-1")).contains(relation);
            assertThat(store.allRelations()).containsExactly(relation);
            assertThat(store.loadEntity("missing")).isEmpty();
            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("present");
            assertThat(store.findRelations("missing")).containsExactly(relation);

            // Saving the real entity materializes the placeholder in place.
            store.saveEntity(entity("missing", "Missing"));

            assertThat(store.loadEntity("missing")).contains(entity("missing", "Missing"));
            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("missing", "present");
        }
    }

    @Test
    void deletingEntitiesCountsPlaceholderVerticesToo() {
        try (var store = newStore()) {
            saveEntities(store, "present");
            store.saveRelation(new RelationRecord("relation-1", "present", "missing", "knows", "d", 0.5d, List.of("chunk-1")));

            // Mirrors the Neo4j DETACH DELETE semantics: the projected endpoint vertex counts as a
            // delete even though the read paths treat it as an absent entity.
            assertThat(store.deleteEntities(List.of("missing"))).isEqualTo(1);

            assertThat(store.allRelations()).isEmpty();
            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("present");
        }
    }

    @Test
    void findsRelationsAcrossIncomingAndOutgoingEdges() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            var first = new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1"));
            var second = new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1"));
            store.saveRelation(first);
            store.saveRelation(second);

            assertThat(store.findRelations("e2")).containsExactly(first, second);
            assertThat(store.findRelations("e1")).containsExactly(first);
            assertThat(store.findRelations("e3")).containsExactly(second);
            assertThat(store.findRelations("ghost")).isEmpty();
        }
    }

    @Test
    void deletingEntitiesCascadesEdgesAndCountsOnlyRealDeletes() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1")));

            assertThat(store.deleteEntities(List.of("e2", "ghost"))).isEqualTo(1);

            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("e1", "e3");
            assertThat(store.allRelations()).isEmpty();
        }
    }

    @Test
    void deletingRelationsCountsOnlyRealDeletes() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1")));

            assertThat(store.deleteRelations(List.of("relation-a", "ghost"))).isEqualTo(1);

            assertThat(store.allRelations()).extracting(RelationRecord::id).containsExactly("relation-b");
            assertThat(store.allEntities()).hasSize(3);
            assertThat(store.deleteEntities(List.of())).isZero();
            assertThat(store.deleteRelations(List.of())).isZero();
        }
    }

    @Test
    void listsEntitiesRelationsAndLabelsInDeterministicOrder() {
        try (var store = newStore()) {
            saveEntities(store, "zeta", "alpha", "mid");
            store.saveRelation(new RelationRecord("relation-b", "alpha", "zeta", "knows", "b", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-a", "alpha", "mid", "knows", "a", 0.5d, List.of("chunk-1")));

            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("alpha", "mid", "zeta");
            assertThat(store.allRelations()).extracting(RelationRecord::id).containsExactly("relation-a", "relation-b");
            assertThat(store.labels()).containsExactly("alpha", "mid", "zeta");
        }
    }

    @Test
    void batchEntityApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore();
             var beta = newStore()) {
            var alphaFirst = entity("entity-1", "Alice-v1");
            var alphaLast = entity("entity-1", "Alice-v2");
            var alphaSecond = entity("entity-2", "Adam");
            var betaEntity = entity("entity-1", "Bob");

            alpha.saveEntities(List.of(alphaFirst, alphaSecond, alphaLast));
            beta.saveEntities(List.of(betaEntity));
            alpha.saveEntities(List.of());

            assertThat(alpha.allEntities()).containsExactly(alphaLast, alphaSecond);
            assertThat(beta.allEntities()).containsExactly(betaEntity);
            assertThat(alpha.loadEntities(List.of("entity-2", "missing", "entity-1", "entity-2")))
                .containsExactly(alphaSecond, alphaLast, alphaSecond);
        }
    }

    @Test
    void batchRelationApisKeepOrderSkipMissingRepeatDuplicates() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3", "e4");
            var first = new RelationRecord("relation-1", "e1", "e2", "knows", "first", 0.9d, List.of("chunk-1"));
            var updated = new RelationRecord("relation-1", "e1", "e2", "knows", "updated", 0.4d, List.of("chunk-2"));
            var second = new RelationRecord("relation-2", "e3", "e4", "knows", "second", 0.5d, List.of("chunk-3"));

            store.saveRelations(List.of(first, second, updated));
            store.saveRelations(List.of());

            assertThat(store.allRelations()).containsExactly(updated, second);
            assertThat(store.loadRelations(List.of("relation-2", "missing", "relation-1", "relation-2")))
                .containsExactly(second, updated, second);
            assertThat(store.loadRelation("relation-1")).contains(updated);
        }
    }

    @Test
    void searchEntitiesByTextMatchesTheCandidateFieldsWithinTheWorkspaceOnly() {
        try (var alpha = newStore();
             var beta = newStore()) {
            alpha.saveEntities(List.of(
                new EntityRecord("e1", "Alice", "person", "", List.of(), List.of()),
                new EntityRecord("e2", "Bob", "researcher", "colleague of Alice", List.of(), List.of()),
                new EntityRecord("e3", "Gamma", "person", "", List.of("ALICE-TWO", "共事"), List.of()),
                new EntityRecord("e4", "Delta", "artifact", "", List.of(), List.of())
            ));
            beta.saveEntity(new EntityRecord("e9", "Alice", "person", "", List.of(), List.of()));

            assertThat(alpha.searchEntitiesByText("alice"))
                .extracting(EntityRecord::id)
                .containsExactly("e1", "e2", "e3");
            assertThat(alpha.searchEntitiesByText("共事"))
                .extracting(EntityRecord::id)
                .containsExactly("e3");
            assertThat(alpha.searchEntitiesByText("  ")).isEmpty();
            assertThat(alpha.searchEntitiesByText("e4")).isEmpty();
            assertThat(beta.searchEntitiesByText("gamma")).isEmpty();
        }
    }

    @Test
    void clearRemovesAllVerticesAndEdges() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));

            store.clear();

            assertThat(store.allEntities()).isEmpty();
            assertThat(store.allRelations()).isEmpty();
            assertThat(store.labels()).isEmpty();
        }
    }

    @Test
    void isolatesWorkspacesInTheSharedSpace() {
        var firstRelation = new RelationRecord("relation-1", "entity-1", "entity-a", "knows", "first", 0.5d, List.of("chunk-1"));
        var secondRelation = new RelationRecord("relation-1", "entity-1", "entity-b", "knows", "second", 0.5d, List.of("chunk-1"));

        try (var first = newStore();
             var second = newStore()) {
            first.saveEntity(entity("entity-1", "Alice"));
            second.saveEntity(entity("entity-1", "Bob"));
            first.saveRelation(firstRelation);
            second.saveRelation(secondRelation);

            assertThat(first.loadEntity("entity-1").map(EntityRecord::name)).contains("Alice");
            assertThat(second.loadEntity("entity-1").map(EntityRecord::name)).contains("Bob");
            assertThat(first.allEntities()).extracting(EntityRecord::id).containsExactly("entity-1");
            assertThat(second.allEntities()).extracting(EntityRecord::id).containsExactly("entity-1");
            assertThat(first.allRelations()).containsExactly(firstRelation);
            assertThat(second.allRelations()).containsExactly(secondRelation);
            assertThat(first.findRelations("entity-a")).containsExactly(firstRelation);
            assertThat(second.findRelations("entity-a")).isEmpty();
        }
    }

    @Test
    void supportsWorkspaceIdsWithSpecialCharacters() {
        var workspace = "ws 混合\"quoted\"🚀" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try (var store = newStore(workspace);
             var other = newStore()) {
            store.saveEntity(entity("entity-1", "Alice"));
            other.saveEntity(entity("entity-1", "Bob"));

            assertThat(store.loadEntity("entity-1")).isPresent();
            assertThat(store.labels()).containsExactly("entity-1");
            assertThat(other.loadEntity("entity-1").map(EntityRecord::name)).contains("Bob");
        }
    }

    @Test
    void dropsAndListsWorkspaceGraphsWithoutTouchingOthers() {
        var firstWorkspace = newWorkspaceId();
        var secondWorkspace = newWorkspaceId();
        var outsiderWorkspace = newWorkspaceId();

        try (var first = newStore(firstWorkspace);
             var second = newStore(secondWorkspace);
             var outsider = newStore(outsiderWorkspace)) {
            first.saveEntity(entity("e1", "Alice"));
            second.saveEntity(entity("e1", "Bob"));
            outsider.saveEntity(entity("e1", "Carol"));
        }

        try (var lifecycle = new NebulaWorkspaceGraphLifecycle(newConfig())) {
            // The workspace id is the graph identifier on this backend, and no family listing exists.
            assertThat(lifecycle.workspaceGraphId(firstWorkspace)).isEqualTo(firstWorkspace);
            assertThat(lifecycle.listWorkspaceGraphIds("ws_")).isEmpty();

            assertThat(lifecycle.dropWorkspaceGraph(firstWorkspace)).isTrue();
            assertThat(lifecycle.dropWorkspaceGraph(firstWorkspace)).isFalse();
        }

        try (var first = newStore(firstWorkspace);
             var second = newStore(secondWorkspace);
             var outsider = newStore(outsiderWorkspace)) {
            assertThat(first.allEntities()).isEmpty();
            assertThat(second.loadEntity("e1")).isPresent();
            assertThat(outsider.loadEntity("e1")).isPresent();
        }
    }

    @Test
    void dropWorkspaceGraphReturnsFalseWhenTheSpaceDoesNotExist() {
        var missingSpaceConfig = new NebulaGraphConfig(
            CLUSTER.host(),
            CLUSTER.port(),
            NebulaGraphConfig.DEFAULT_USERNAME,
            NebulaGraphConfig.DEFAULT_PASSWORD,
            "missing_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
            1,
            1
        );

        try (var lifecycle = new NebulaWorkspaceGraphLifecycle(missingSpaceConfig)) {
            assertThat(lifecycle.dropWorkspaceGraph(newWorkspaceId())).isFalse();
        }
    }

    @Test
    void loadsMissingRecordsAsEmpty() {
        try (var store = newStore()) {
            assertThat(store.loadEntity("ghost")).isEmpty();
            assertThat(store.loadRelation("ghost")).isEmpty();
        }
    }

    @Test
    void loadsLegacyVerticesWithoutFilePathPropertyAsEmpty() {
        var workspace = newWorkspaceId();
        try (var ignored = newStore(workspace)) {
            // Bootstraps the shared space so the raw insert below has a schema to write against.
        }
        withRawSession(session -> {
            execute(session,
                "INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, workspace_id, materialized) VALUES "
                    + NebulaSupport.quoted(NebulaSupport.scopedVid(workspace, "legacy-1"))
                    + ":(\"legacy-1\", \"Legacy\", \"person\", \"written before file_path existed\", \"\", \"chunk-1\", "
                    + NebulaSupport.quoted(workspace) + ", true);");
            return null;
        });

        try (var store = newStore(workspace)) {
            assertThat(store.loadEntity("legacy-1")).get()
                .extracting(EntityRecord::filePath)
                .isEqualTo("");
        }
    }

    @Test
    void upgradesALegacySpaceWithoutAttributesAndKeepsMultibyteBagsAcrossRestarts() {
        var space = "upgrade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var config = newConfig(space);
        var workspace = newWorkspaceId();
        try {
            createLegacySpace(space);
            withBootstrapSession(session -> {
                execute(session, "USE `" + space + "`;");
                writeLegacyRows(session, workspace);
                return null;
            });

            var largeText = "知".repeat(4000);
            var entityAttributes = Map.<String, Object>of("note", largeText, "标签", List.of("甲", "乙"));
            var relationAttributes = Map.<String, Object>of("备注", largeText);

            try (var store = new NebulaGraphStore(config, workspace)) {
                assertThat(store.loadEntity("legacy-1").orElseThrow().attributes()).isEmpty();
                assertThat(store.loadRelation("legacy-rel-1").orElseThrow().attributes()).isEmpty();

                store.saveEntity(new EntityRecord(
                    "entity-1", "Alice", "person", "researcher", List.of(), List.of("chunk-1"),
                    "", entityAttributes));
                store.saveRelation(new RelationRecord(
                    "relation-1", "entity-1", "entity-2", "knows", "linked", 0.5d,
                    "chunk-1", "", relationAttributes));
            }

            // A second bootstrap over the upgraded space must be idempotent and keep the bags.
            try (var restarted = new NebulaGraphStore(config, workspace)) {
                assertThat(restarted.loadEntity("entity-1").orElseThrow().attributes())
                    .isEqualTo(entityAttributes);
                assertThat(restarted.loadRelation("relation-1").orElseThrow().attributes())
                    .isEqualTo(relationAttributes);
            }
        } finally {
            dropSpace(space);
        }
    }

    @Test
    void knowledgeGraphViewsMatchTheDefaultImplementationOnTheNebulaGraphBackend() {
        try (var store = newStore()) {
            store.saveEntities(GraphViewParity.ENTITIES);
            store.saveRelations(GraphViewParity.RELATIONS);

            GraphViewParity.assertParityWithDefaultImplementation(store);
        }
    }

    @Test
    void executeCypherIsRejectedOnTheNebulaGraphBackend() {
        try (var store = newStore()) {
            assertThatThrownBy(() -> store.executeCypher("MATCH (n:entity) RETURN n", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not support native Cypher execution");
        }
    }

    private static void saveEntities(NebulaGraphStore store, String... ids) {
        for (var id : ids) {
            store.saveEntity(new EntityRecord(
                id,
                id.toUpperCase(Locale.ROOT),
                "type",
                "description of " + id,
                List.of(),
                List.of("chunk-1")
            ));
        }
    }

    private static EntityRecord entity(String id, String name) {
        return new EntityRecord(id, name, "person", name + " description", List.of(), List.of("chunk-" + id));
    }

    private static String newWorkspaceId() {
        return "ws_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static NebulaGraphStore newStore() {
        return newStore(newWorkspaceId());
    }

    private static NebulaGraphStore newStore(String workspaceId) {
        return new NebulaGraphStore(newConfig(), workspaceId);
    }

    private static NebulaGraphConfig newConfig() {
        return newConfig(SPACE);
    }

    private static NebulaGraphConfig newConfig(String space) {
        return new NebulaGraphConfig(
            CLUSTER.host(),
            CLUSTER.port(),
            NebulaGraphConfig.DEFAULT_USERNAME,
            NebulaGraphConfig.DEFAULT_PASSWORD,
            space,
            1,
            1
        );
    }

    private static void createLegacySpace(String space) {
        withBootstrapSession(session -> {
            execute(session, "CREATE SPACE IF NOT EXISTS `" + space + "` "
                + "(partition_num = 1, replica_factor = 1, vid_type = FIXED_STRING(64));");
            awaitSpaceUsable(session, space);
            execute(session, "USE `" + space + "`;");
            execute(session, "CREATE TAG IF NOT EXISTS entity("
                + "entity_id string, name string, entity_type string, description string, aliases string, "
                + "source_id string, file_path string, workspace_id string, materialized bool);");
            execute(session, "CREATE EDGE IF NOT EXISTS directed("
                + "relation_id string, src_id string, tgt_id string, keywords string, description string, "
                + "weight double, source_id string, file_path string, workspace_id string);");
            execute(session, "CREATE TAG INDEX IF NOT EXISTS idx_entity_workspace_id "
                + "ON entity(workspace_id(64));");
            execute(session, "CREATE EDGE INDEX IF NOT EXISTS idx_directed_workspace_id "
                + "ON directed(workspace_id(64));");
            execute(session, "CREATE EDGE INDEX IF NOT EXISTS idx_directed_workspace_relation_id "
                + "ON directed(workspace_id(64), relation_id(64));");
            return null;
        });
    }

    private static void writeLegacyRows(Session session, String workspace) {
        var alice = NebulaSupport.quoted(NebulaSupport.scopedVid(workspace, "legacy-1"));
        var bob = NebulaSupport.quoted(NebulaSupport.scopedVid(workspace, "legacy-2"));
        retryUntilSchemaWritable(session, "INSERT VERTEX entity(entity_id, name, entity_type, description, "
            + "aliases, source_id, file_path, workspace_id, materialized) VALUES "
            + alice + ":(\"legacy-1\", \"Legacy Alice\", \"person\", \"before attributes\", \"\", \"chunk-1\", "
            + "\"\", " + NebulaSupport.quoted(workspace) + ", true);");
        retryUntilSchemaWritable(session, "INSERT VERTEX entity(entity_id, name, entity_type, description, "
            + "aliases, source_id, file_path, workspace_id, materialized) VALUES "
            + bob + ":(\"legacy-2\", \"Legacy Bob\", \"person\", \"before attributes\", \"\", \"chunk-2\", "
            + "\"\", " + NebulaSupport.quoted(workspace) + ", true);");
        retryUntilSchemaWritable(session, "INSERT EDGE directed(relation_id, src_id, tgt_id, keywords, description, "
            + "weight, source_id, file_path, workspace_id) VALUES " + alice + "->" + bob
            + ":(\"legacy-rel-1\", \"legacy-1\", \"legacy-2\", \"knows\", \"before attributes\", 0.5, "
            + "\"chunk-1\", \"\", " + NebulaSupport.quoted(workspace) + ");");
    }

    private static void awaitSpaceUsable(Session session, String space) {
        var deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                if (session.execute("USE `" + space + "`;").isSucceeded()) {
                    return;
                }
            } catch (IOErrorException exception) {
                throw new IllegalStateException("USE " + space + " failed", exception);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("space " + space + " did not become usable");
            }
            sleep();
        }
    }

    private static void retryUntilSchemaWritable(Session session, String statement) {
        var deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                if (session.execute(statement).isSucceeded()) {
                    return;
                }
            } catch (IOErrorException exception) {
                throw new IllegalStateException("legacy schema probe failed", exception);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("legacy schema did not accept writes: " + statement);
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the legacy schema", interruptedException);
        }
    }

    private static void dropSpace(String space) {
        try {
            withBootstrapSession(session -> execute(session, "DROP SPACE `" + space + "`;"));
        } catch (RuntimeException ignored) {
            // Cleanup must not mask the assertion outcome; the test cluster is disposable.
        }
    }

    private static <T> T withRawSession(Function<Session, T> work) {
        return withSession(SPACE, work);
    }

    private static <T> T withBootstrapSession(Function<Session, T> work) {
        return withSession(null, work);
    }

    private static <T> T withSession(String space, Function<Session, T> work) {
        var pool = new NebulaPool();
        try {
            if (!pool.init(List.of(new HostAddress(CLUSTER.host(), CLUSTER.port())), new NebulaPoolConfig())) {
                throw new IllegalStateException("NebulaGraph graphd is unreachable");
            }
            var session = pool.getSession(
                NebulaGraphConfig.DEFAULT_USERNAME,
                NebulaGraphConfig.DEFAULT_PASSWORD,
                true
            );
            try {
                if (space != null) {
                    execute(session, "USE `" + space + "`;");
                }
                return work.apply(session);
            } finally {
                session.release();
            }
        } catch (UnknownHostException | InvalidConfigException exception) {
            throw new IllegalStateException("raw session pool failed to initialise", exception);
        } catch (NotValidConnectionException | IOErrorException | AuthFailedException
                 | ClientServerIncompatibleException exception) {
            throw new IllegalStateException("raw session failed to open", exception);
        } finally {
            pool.close();
        }
    }

    private static ResultSet execute(Session session, String statement) {
        try {
            var result = session.execute(statement);
            if (!result.isSucceeded()) {
                throw new IllegalStateException(
                    "statement failed: [%d] %s".formatted(result.getErrorCode(), result.getErrorMessage())
                );
            }
            return result;
        } catch (IOErrorException exception) {
            throw new IllegalStateException("statement failed: " + statement, exception);
        }
    }

    private static List<String> columnValues(ResultSet resultSet, String column) {
        var values = new ArrayList<String>();
        for (var index = 0; index < resultSet.rowsSize(); index++) {
            try {
                values.add(resultSet.rowValues(index).get(column).asString());
            } catch (InvalidValueException | UnsupportedEncodingException exception) {
                throw new IllegalStateException("unexpected value in column " + column, exception);
            }
        }
        return List.copyOf(values);
    }
}
