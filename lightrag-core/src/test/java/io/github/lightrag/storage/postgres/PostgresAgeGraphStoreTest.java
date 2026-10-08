package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import io.github.lightrag.support.GraphViewParity;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@Testcontainers
class PostgresAgeGraphStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newAgeContainer();

    @Test
    void bootstrapsGraphWithLabelsAndIndexesIdempotently() {
        try (var resources = newResources()) {
            // Replaying the bootstrap (a second provider construction) must be a no-op.
            new PostgresAgeBootstrap(resources.dataSource(), resources.workspaceId()).bootstrap();

            assertThat(queryStrings(
                resources.dataSource(),
                """
                SELECT l.name::text
                FROM ag_catalog.ag_label l
                JOIN ag_catalog.ag_graph g ON l.graph = g.graphid
                WHERE g.name = left(?::text, 63)::name
                """,
                resources.graphName()
            )).contains("base", "DIRECTED");

            assertThat(queryStrings(
                resources.dataSource(),
                "SELECT extversion FROM pg_extension WHERE extname = 'age'"
            )).isNotEmpty();

            assertThat(queryStrings(
                resources.dataSource(),
                "SELECT indexname FROM pg_indexes WHERE schemaname = ?::name",
                resources.graphName()
            )).contains(
                "vertex_idx_node_id",
                "edge_sid_idx",
                "edge_eid_idx",
                "edge_seid_idx",
                "directed_p_idx",
                "directed_eid_idx",
                "directed_sid_idx",
                "directed_seid_idx",
                "entity_p_idx",
                "entity_idx_node_id",
                "entity_node_id_gin_idx"
            );
        }
    }

    @Test
    void roundTripsEntitiesAndRelationsWithSpecialCharacters() {
        try (var resources = newResources()) {
            var store = resources.store();
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
        try (var resources = newResources()) {
            var store = resources.store();
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
        try (var resources = newResources()) {
            var store = resources.store();
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
    void rejectsRelationsWhoseEndpointsAreMissingAndLeavesTheGraphUntouched() {
        try (var resources = newResources()) {
            var store = resources.store();
            saveEntities(store, "present");

            var relation = new RelationRecord("relation-1", "present", "missing", "knows", "d", 0.5d, List.of("chunk-1"));

            assertThatThrownBy(() -> store.saveRelation(relation))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("missing endpoint(s) [missing]")
                .hasMessageContaining("AGE reports no error");

            assertThat(store.allRelations()).isEmpty();
            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("present");
        }
    }

    @Test
    void findsRelationsAcrossIncomingAndOutgoingEdges() {
        try (var resources = newResources()) {
            var store = resources.store();
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
        try (var resources = newResources()) {
            var store = resources.store();
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
        try (var resources = newResources()) {
            var store = resources.store();
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
        try (var resources = newResources()) {
            var store = resources.store();
            saveEntities(store, "zeta", "alpha", "mid");
            store.saveRelation(new RelationRecord("relation-b", "alpha", "zeta", "knows", "b", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-a", "alpha", "mid", "knows", "a", 0.5d, List.of("chunk-1")));

            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("alpha", "mid", "zeta");
            assertThat(store.allRelations()).extracting(RelationRecord::id).containsExactly("relation-a", "relation-b");
            assertThat(store.labels()).containsExactly("alpha", "mid", "zeta");
        }
    }

    @Test
    void searchesEntitiesByTextNativelyAcrossTheCandidateFields() {
        try (var resources = newResources()) {
            var store = resources.store();
            store.saveEntities(List.of(
                new EntityRecord("e1", "Alice", "person", "", List.of(), List.of("chunk-1")),
                new EntityRecord("e2", "Bob", "researcher", "colleague of Alice", List.of(), List.of("chunk-1")),
                new EntityRecord("e3", "Gamma", "person", "", List.of("ALICE-TWO", "共事"), List.of("chunk-1")),
                new EntityRecord("e4", "Delta", "artifact", "", List.of(), List.of("chunk-1"))
            ));

            // Name, description and alias matches, case-insensitive, in id order.
            assertThat(store.searchEntitiesByText("alice"))
                .extracting(EntityRecord::id)
                .containsExactly("e1", "e2", "e3");
            assertThat(store.searchEntitiesByText("共事"))
                .extracting(EntityRecord::id)
                .containsExactly("e3");
            assertThat(store.searchEntitiesByText("  ")).isEmpty();
            assertThat(store.searchEntitiesByText("e4")).isEmpty();
        }
    }

    @Test
    void clearRemovesAllVerticesAndEdges() {
        try (var resources = newResources()) {
            var store = resources.store();
            saveEntities(store, "e1", "e2");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));

            store.clear();

            assertThat(store.allEntities()).isEmpty();
            assertThat(store.allRelations()).isEmpty();
            assertThat(store.labels()).isEmpty();
        }
    }

    @Test
    void isolatesWorkspacesInSeparateGraphs() {
        try (var first = newResources(); var second = newResources()) {
            first.store().saveEntity(new EntityRecord("entity-1", "Alice", "person", "first", List.of(), List.of()));
            second.store().saveEntity(new EntityRecord("entity-1", "Bob", "person", "second", List.of(), List.of()));

            assertThat(first.graphName()).isNotEqualTo(second.graphName());
            assertThat(first.store().loadEntity("entity-1").map(EntityRecord::name)).contains("Alice");
            assertThat(second.store().loadEntity("entity-1").map(EntityRecord::name)).contains("Bob");
        }
    }

    @Test
    void supportsMixedCaseWorkspaceGraphNames() {
        var workspace = "MixedCase" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (var resources = newResources(workspace)) {
            assertThat(resources.graphName()).isEqualTo(workspace + "_chunk_entity_relation");

            resources.store().saveEntity(new EntityRecord("entity-1", "Alice", "person", "d", List.of(), List.of("chunk-1")));

            assertThat(resources.store().loadEntity("entity-1")).isPresent();
            assertThat(resources.store().labels()).containsExactly("entity-1");
            assertThat(queryStrings(
                resources.dataSource(),
                "SELECT indexname FROM pg_indexes WHERE schemaname = ?::name",
                resources.graphName()
            )).contains("entity_idx_node_id");
        }
    }

    @Test
    void replayingTheBootstrapKeepsExistingGraphData() {
        try (var resources = newResources()) {
            var entity = new EntityRecord("entity-1", "Alice", "person", "d", List.of("A"), List.of("chunk-1"));
            resources.store().saveEntity(entity);

            new PostgresAgeBootstrap(resources.dataSource(), resources.workspaceId()).bootstrap();

            assertThat(resources.store().loadEntity("entity-1")).contains(entity);
        }
    }

    @Test
    void loadsMissingRecordsAsEmpty() {
        try (var resources = newResources()) {
            assertThat(resources.store().loadEntity("ghost")).isEmpty();
            assertThat(resources.store().loadRelation("ghost")).isEmpty();
        }
    }

    @Test
    void loadsLegacyVerticesWithoutFilePathPropertyAsEmpty() {
        try (var resources = newResources()) {
            executeCypher(resources, """
                CREATE (:base {entity_id: 'legacy-1', name: 'Legacy', entity_type: 'person',
                               description: 'written before file_path existed',
                               aliases: [], source_id: 'chunk-1'})
                """);

            assertThat(resources.store().loadEntity("legacy-1")).get()
                .extracting(EntityRecord::filePath)
                .isEqualTo("");
        }
    }

    @Test
    void knowledgeGraphViewsMatchTheDefaultImplementationOnTheAgeBackend() {
        try (var resources = newResources()) {
            resources.store().saveEntities(GraphViewParity.ENTITIES);
            resources.store().saveRelations(GraphViewParity.ENDPOINT_COMPLETE_RELATIONS);

            GraphViewParity.assertParityOnEndpointCompleteGraph(resources.store());
        }
    }

    @Test
    void bulkReadsMatchSingleReadsInOrderAndSkipMissingIds() {
        try (var resources = newResources()) {
            var store = resources.store();
            saveEntities(store, "e1", "e2", "e3");
            saveRelation(store, "r1", "e1", "e2");
            saveRelation(store, "r2", "e1", "e3");
            saveRelation(store, "r3", "e2", "e2");

            var entityIds = List.of("e2", "missing", "e1", "e2");
            var expectedEntities = new ArrayList<EntityRecord>();
            for (var id : entityIds) {
                store.loadEntity(id).ifPresent(expectedEntities::add);
            }
            assertThat(store.loadEntities(entityIds)).isEqualTo(expectedEntities);

            var relationIds = List.of("r3", "ghost", "r1", "r3");
            var expectedRelations = new ArrayList<RelationRecord>();
            for (var id : relationIds) {
                store.loadRelation(id).ifPresent(expectedRelations::add);
            }
            assertThat(store.loadRelations(relationIds)).isEqualTo(expectedRelations);

            var adjacencyIds = List.of("e2", "ghost", "e1", "e4", "e1");
            var expectedAdjacency = new LinkedHashMap<String, List<RelationRecord>>();
            for (var id : adjacencyIds) {
                expectedAdjacency.put(id, store.findRelations(id));
            }
            var adjacency = store.findRelations(adjacencyIds);
            assertThat(adjacency.keySet()).containsExactlyElementsOf(expectedAdjacency.keySet());
            for (var id : adjacencyIds) {
                assertThat(adjacency.get(id)).containsExactlyElementsOf(expectedAdjacency.get(id));
            }
            assertThat(adjacency.get("ghost")).isEmpty();
            assertThat(adjacency.get("e4")).isEmpty();
            assertThatThrownBy(() -> adjacency.put("x", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void bulkReadsShareOneConnectionPerBatch() {
        var dataSource = newCountingDataSource();
        try {
            var workspaceId = "ws_" + UUID.randomUUID().toString().replace("-", "");
            new PostgresAgeBootstrap(dataSource, workspaceId).bootstrap();
            var store = new PostgresAgeGraphStore(dataSource, workspaceId);
            saveEntities(store, "e1", "e2", "e3");
            saveRelation(store, "r1", "e1", "e2");
            saveRelation(store, "r2", "e2", "e3");

            dataSource.resetConnectionCount();
            assertThat(store.loadEntities(List.of("e1", "e2", "e3"))).hasSize(3);
            assertThat(dataSource.connectionCount()).isEqualTo(1);

            dataSource.resetConnectionCount();
            assertThat(store.loadRelations(List.of("r1", "r2"))).hasSize(2);
            assertThat(dataSource.connectionCount()).isEqualTo(1);

            dataSource.resetConnectionCount();
            assertThat(store.findRelations(List.of("e1", "e2", "e3"))).hasSize(3);
            assertThat(dataSource.connectionCount()).isEqualTo(1);

            dataSource.resetConnectionCount();
            assertThat(store.degrees(List.of("e1", "e2", "e3")))
                .containsExactly(entry("e1", 1), entry("e2", 2), entry("e3", 1));
            assertThat(dataSource.connectionCount()).isEqualTo(1);
        } finally {
            dataSource.close();
        }
    }

    @Test
    void nativeDegreesMatchFindRelationsSizesWithZeroFill() {
        try (var resources = newResources()) {
            var store = resources.store();
            saveEntities(store, "e1", "e2", "e3", "e4");
            saveRelation(store, "r1", "e1", "e2");
            saveRelation(store, "r2", "e1", "e3");
            saveRelation(store, "r3", "e2", "e2");

            var ids = List.of("e2", "ghost", "e1", "e4");
            var degrees = store.degrees(ids);

            assertThat(degrees).containsExactly(
                entry("e2", 2),
                entry("ghost", 0),
                entry("e1", 2),
                entry("e4", 0)
            );
            for (var id : ids) {
                assertThat(degrees.get(id)).isEqualTo(store.findRelations(id).size());
            }
            assertThat(store.degrees(List.of())).isEmpty();
            assertThatThrownBy(() -> degrees.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void executesAdHocCypherWithParametersAndConvertsValues() {
        try (var resources = newResources()) {
            var store = resources.store();
            store.saveEntity(new EntityRecord("e1", "Alice", "person", "orig", List.of("A"), List.of("chunk-1")));
            store.saveEntity(new EntityRecord("e2", "Bob", "researcher", "other", List.of(), List.of("chunk-1")));

            var scalars = store.executeCypher(
                "MATCH (n:base {entity_id: $id}) RETURN n.name AS name, n.entity_type AS type",
                Map.of("id", "e1"));
            assertThat(scalars.columns()).containsExactly("name", "type");
            assertThat(scalars.records()).containsExactly(Map.of("name", "Alice", "type", "person"));

            var ordered = store.executeCypher(
                "MATCH (n:base) RETURN n.entity_id ORDER BY n.entity_id DESC LIMIT 5",
                Map.of());
            assertThat(ordered.columns()).containsExactly("n.entity_id");
            assertThat(ordered.records()).extracting(record -> record.get("n.entity_id"))
                .containsExactly("e2", "e1");

            var vertices = store.executeCypher("MATCH (n:base {entity_id: 'e1'}) RETURN n", Map.of());
            assertThat(vertices.columns()).containsExactly("n");
            var vertex = (Map<?, ?>) vertices.records().get(0).get("n");
            assertThat(vertex.get("label")).isEqualTo("base");
            assertThat(vertex.get("id")).isInstanceOf(Number.class);
            var properties = (Map<?, ?>) vertex.get("properties");
            assertThat(properties.get("entity_id")).isEqualTo("e1");
            assertThat(properties.get("name")).isEqualTo("Alice");
        }
    }

    @Test
    void executesMutatingCypherWithoutReturn() {
        try (var resources = newResources()) {
            var store = resources.store();
            store.saveEntity(new EntityRecord("e1", "Alice", "person", "orig", List.of(), List.of("chunk-1")));

            var result = store.executeCypher(
                "MATCH (n:base {entity_id: $id}) SET n.description = $description",
                Map.of("id", "e1", "description", "updated by cypher"));

            assertThat(result.columns()).isEmpty();
            assertThat(result.records()).isEmpty();
            assertThat(store.loadEntity("e1").map(EntityRecord::description)).contains("updated by cypher");
        }
    }

    @Test
    void executeCypherRejectsReturnStar() {
        try (var resources = newResources()) {
            assertThatThrownBy(() -> resources.store().executeCypher("MATCH (n:base) RETURN *", Map.of()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("RETURN *");
        }
    }

    private static void saveEntities(PostgresAgeGraphStore store, String... ids) {
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

    private static void saveRelation(PostgresAgeGraphStore store, String relationId, String srcId, String tgtId) {
        store.saveRelation(new RelationRecord(
            relationId,
            srcId,
            tgtId,
            "knows",
            "description of " + relationId,
            1.0d,
            List.of("chunk-1")
        ));
    }

    private static PostgreSQLContainer<?> newAgeContainer() {
        var image = DockerImageName.parse(
            System.getenv().getOrDefault("LIGHTRAG_AGE_IMAGE", "apache/age:release_PG16_1.6.0")
        ).asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static Resources newResources() {
        return newResources("ws_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Resources newResources(String workspaceId) {
        var dataSource = newDataSource();
        new PostgresAgeBootstrap(dataSource, workspaceId).bootstrap();
        return new Resources(
            new PostgresAgeGraphStore(dataSource, workspaceId),
            dataSource,
            workspaceId,
            PostgresAgeSupport.graphName(workspaceId)
        );
    }

    private static HikariDataSource newDataSource() {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikariConfig.setUsername(POSTGRES.getUsername());
        hikariConfig.setPassword(POSTGRES.getPassword());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static CountingDataSource newCountingDataSource() {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikariConfig.setUsername(POSTGRES.getUsername());
        hikariConfig.setPassword(POSTGRES.getPassword());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new CountingDataSource(hikariConfig);
    }

    /** Counts checkout requests so a test can pin how many sessions an operation opens. */
    private static final class CountingDataSource extends HikariDataSource {
        private final AtomicInteger connectionCount = new AtomicInteger();

        private CountingDataSource(HikariConfig hikariConfig) {
            super(hikariConfig);
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionCount.incrementAndGet();
            return super.getConnection();
        }

        private int connectionCount() {
            return connectionCount.get();
        }

        private void resetConnectionCount() {
            connectionCount.set(0);
        }
    }

    private static List<String> queryStrings(HikariDataSource dataSource, String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            var values = new ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void executeCypher(Resources resources, String cypher) {
        try (var connection = resources.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var pathStatement = connection.createStatement()) {
                pathStatement.execute("SET LOCAL search_path = ag_catalog, \"$user\", public");
            }
            try (var statement = connection.createStatement()) {
                statement.execute(
                    "SELECT * FROM ag_catalog.cypher("
                        + PostgresAgeSupport.dollarQuote(resources.graphName()) + ", "
                        + PostgresAgeSupport.dollarQuote(cypher) + ") AS (result ag_catalog.agtype)"
                );
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> queryStrings(HikariDataSource dataSource, String sql, String parameter) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var resultSet = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Resources(
        PostgresAgeGraphStore store,
        HikariDataSource dataSource,
        String workspaceId,
        String graphName
    ) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
