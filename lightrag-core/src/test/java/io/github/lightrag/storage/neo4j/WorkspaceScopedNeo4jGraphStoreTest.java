package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.support.Neo4jTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceScopedNeo4jGraphStoreTest {
    @Container
    private static final Neo4jContainer<?> NEO4J = Neo4jTestContainers.create();

    @BeforeEach
    void resetGraph() {
        try (var driver = GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
             var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run("MATCH (node) DETACH DELETE node");
                return null;
            });
        }
    }

    @Test
    void savesAndLoadsEntityWithinWorkspaceOnly() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alice = entity("entity-1", "Alice");
            var bob = entity("entity-1", "Bob");

            alpha.saveEntity(alice);
            beta.saveEntity(bob);

            assertThat(alpha.loadEntity("entity-1")).contains(alice);
            assertThat(beta.loadEntity("entity-1")).contains(bob);
            assertThat(alpha.allEntities()).containsExactly(alice);
            assertThat(beta.allEntities()).containsExactly(bob);
        }
    }

    @Test
    void savesRelationWithWorkspaceScopedPlaceholders() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var relation = relation("relation-1", "entity-1", "entity-2", "alpha knows placeholder");
            var betaEntity = entity("entity-1", "Bob");

            alpha.saveRelation(relation);
            beta.saveEntity(betaEntity);

            assertThat(alpha.findRelations("entity-1")).containsExactly(relation);
            assertThat(beta.findRelations("entity-1")).isEmpty();
            assertThat(beta.loadEntity("entity-1")).contains(betaEntity);
        }
    }

    @Test
    void savesRelationWithUpstreamPropertyNames() {
        try (var alpha = newStore("alpha")) {
            var relation = new GraphStore.RelationRecord(
                "rel-1",
                "entity-1",
                "entity-2",
                "depends_on, owned_by",
                "entity one is related to entity two",
                0.9d,
                "chunk-1<SEP>chunk-2",
                "/tmp/doc-a.md<SEP>/tmp/doc-b.md"
            );

            alpha.saveRelation(relation);

            var properties = readSingleRelationProperties();

            assertThat(properties).containsKeys(
                "workspaceId",
                "scopedId",
                "relation_id",
                "src_id",
                "tgt_id",
                "keywords",
                "description",
                "weight",
                "source_id",
                "file_path"
            );
            assertThat(properties).doesNotContainKeys("id", "sourceId", "filePath");
            assertThat(properties).containsEntry("relation_id", "rel-1");
            assertThat(properties).containsEntry("src_id", "entity-1");
            assertThat(properties).containsEntry("tgt_id", "entity-2");
            assertThat(properties).containsEntry("keywords", "depends_on, owned_by");
            assertThat(properties).containsEntry("source_id", "chunk-1<SEP>chunk-2");
            assertThat(properties).containsEntry("file_path", "/tmp/doc-a.md<SEP>/tmp/doc-b.md");
        }
    }

    @Test
    void listsEntitiesRelationsAndNeighborsWithinCurrentWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaEntity = entity("entity-1", "Alice");
            var alphaRelation = relation("relation-1", "entity-1", "entity-2", "Alice knows Adam");
            var betaEntity = entity("entity-1", "Bob");
            var betaRelation = relation("relation-1", "entity-1", "entity-3", "Bob knows Ben");

            alpha.saveEntity(alphaEntity);
            alpha.saveRelation(alphaRelation);
            beta.saveEntity(betaEntity);
            beta.saveRelation(betaRelation);

            assertThat(alpha.allEntities()).containsExactly(alphaEntity);
            assertThat(alpha.allRelations()).containsExactly(alphaRelation);
            assertThat(alpha.findRelations("entity-1")).containsExactly(alphaRelation);

            assertThat(beta.allEntities()).containsExactly(betaEntity);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
            assertThat(beta.findRelations("entity-1")).containsExactly(betaRelation);
        }
    }

    @Test
    void restoreOnlyReplacesCurrentWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaOriginal = entity("entity-1", "Alice");
            var alphaReplacement = entity("entity-2", "Carol");
            var alphaReplacementRelation = relation("relation-2", "entity-2", "entity-4", "Carol reports to Dave");
            var betaEntity = entity("entity-1", "Bob");
            var betaRelation = relation("relation-1", "entity-1", "entity-3", "Bob knows Ben");

            alpha.saveEntity(alphaOriginal);
            beta.saveEntity(betaEntity);
            beta.saveRelation(betaRelation);

            alpha.restore(new Neo4jGraphSnapshot(
                List.of(alphaReplacement),
                List.of(alphaReplacementRelation)
            ));

            assertThat(alpha.loadEntity("entity-1")).isEmpty();
            assertThat(alpha.loadEntity("entity-2")).contains(alphaReplacement);
            assertThat(alpha.allRelations()).containsExactly(alphaReplacementRelation);

            assertThat(beta.loadEntity("entity-1")).contains(betaEntity);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
        }
    }

    @Test
    void bootstrappingWorkspaceStoreDropsLegacyGlobalIdConstraints() {
        installLegacyGlobalIdConstraints();

        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaEntity = entity("住房公积金", "住房公积金-alpha");
            var betaEntity = entity("住房公积金", "住房公积金-beta");
            var alphaRelation = relation(
                "relation:住房公积金:覆盖城市",
                "住房公积金",
                "覆盖城市",
                "alpha relation"
            );
            var betaRelation = relation(
                "relation:住房公积金:覆盖城市",
                "住房公积金",
                "覆盖城市",
                "beta relation"
            );

            alpha.saveEntity(alphaEntity);
            beta.saveEntity(betaEntity);
            alpha.saveRelation(alphaRelation);
            beta.saveRelation(betaRelation);

            assertThat(alpha.loadEntity("住房公积金")).contains(alphaEntity);
            assertThat(beta.loadEntity("住房公积金")).contains(betaEntity);
            assertThat(alpha.allRelations()).containsExactly(alphaRelation);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
        }
    }

    @Test
    void batchEntityApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
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
    void batchRelationApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaFirst = relation("relation-1", "entity-1", "entity-2", "alpha-v1");
            var alphaLast = relation("relation-1", "entity-3", "entity-4", "alpha-v2");
            var alphaSecond = relation("relation-2", "entity-3", "entity-1", "alpha-second");
            var betaRelation = relation("relation-1", "entity-7", "entity-8", "beta");

            alpha.saveRelations(List.of(alphaFirst, alphaSecond, alphaLast));
            beta.saveRelations(List.of(betaRelation));
            alpha.saveRelations(List.of());

            assertThat(alpha.allRelations()).containsExactly(alphaLast, alphaSecond);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
            assertThat(alpha.loadRelations(List.of("relation-2", "missing", "relation-1", "relation-2")))
                .containsExactly(alphaSecond, alphaLast, alphaSecond);
        }
    }

    @Test
    void workspaceScopedNeo4jGraphStoreProvidesNativeBatchOverrides() throws NoSuchMethodException {
        assertOverrides("saveEntities", List.class);
        assertOverrides("saveRelations", List.class);
        assertOverrides("loadEntities", List.class);
        assertOverrides("loadRelations", List.class);
    }

    @Test
    void findRelationsQueriesUseScopedIdsInCypher() {
        var queries = new CopyOnWriteArrayList<String>();

        try (var store = new WorkspaceScopedNeo4jGraphStore(recordingDriver(queries), "neo4j", new WorkspaceScope("alpha"))) {
            queries.clear();

            store.findRelations("entity-1");
            store.findRelations(List.of("entity-1", "entity-2"));
        }

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0))
            .contains("scopedId: $scopedEntityId")
            .doesNotContain("id: $id");
        assertThat(queries.get(1))
            .contains("$scopedEntityIds")
            .contains("scopedId: scopedEntityId")
            .doesNotContain("id: entityId");
    }

    private static WorkspaceScopedNeo4jGraphStore newStore(String workspaceId) {
        return new WorkspaceScopedNeo4jGraphStore(newNeo4jConfig(), new WorkspaceScope(workspaceId));
    }

    private static Neo4jGraphConfig newNeo4jConfig() {
        return new Neo4jGraphConfig(
            NEO4J.getBoltUrl(),
            "neo4j",
            NEO4J.getAdminPassword(),
            "neo4j"
        );
    }

    private static GraphStore.EntityRecord entity(String id, String name) {
        return new GraphStore.EntityRecord(
            id,
            name,
            "person",
            name + " description",
            List.of(),
            List.of("chunk-" + id)
        );
    }

    private static GraphStore.RelationRecord relation(
        String relationId,
        String sourceEntityId,
        String targetEntityId,
        String description
    ) {
        return new GraphStore.RelationRecord(
            relationId,
            sourceEntityId,
            targetEntityId,
            "knows",
            description,
            0.9d,
            List.of("chunk-" + relationId)
        );
    }

    private static void installLegacyGlobalIdConstraints() {
        try (var driver = GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
             var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run(
                    """
                    CREATE CONSTRAINT neo4j_entity_id IF NOT EXISTS
                    FOR (entity:Entity) REQUIRE entity.id IS UNIQUE
                    """
                );
                tx.run(
                    """
                    CREATE CONSTRAINT neo4j_relation_id IF NOT EXISTS
                    FOR ()-[relation:RELATION]-() REQUIRE relation.id IS UNIQUE
                    """
                );
                return null;
            });
        }
    }

    private static void assertOverrides(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = WorkspaceScopedNeo4jGraphStore.class.getMethod(methodName, parameterTypes);
        assertThat(method.getDeclaringClass()).isEqualTo(WorkspaceScopedNeo4jGraphStore.class);
    }

    private static java.util.Map<String, Object> readSingleRelationProperties() {
        try (var driver = GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
             var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            return session.executeRead(tx -> tx.run(
                """
                MATCH ()-[relation:RELATION]->()
                RETURN properties(relation) AS props
                """
            ).single().get("props").asMap());
        }
    }

    private static Driver recordingDriver(List<String> queries) {
        var emptyResult = (Result) Proxy.newProxyInstance(
            Result.class.getClassLoader(),
            new Class<?>[]{Result.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasNext" -> false;
                case "list" -> List.of();
                case "close" -> null;
                default -> unsupported(method.getName());
            }
        );
        var tx = (TransactionContext) Proxy.newProxyInstance(
            TransactionContext.class.getClassLoader(),
            new Class<?>[]{TransactionContext.class},
            (proxy, method, args) -> {
                if ("run".equals(method.getName())) {
                    queries.add((String) args[0]);
                    return emptyResult;
                }
                return unsupported(method.getName());
            }
        );
        var session = Proxy.newProxyInstance(
            Driver.class.getClassLoader(),
            new Class<?>[]{org.neo4j.driver.Session.class},
            (proxy, method, args) -> {
                if ("executeRead".equals(method.getName()) || "executeWrite".equals(method.getName())) {
                    return invokeTransactionCallback(args[0], tx);
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                return unsupported(method.getName());
            }
        );
        return (Driver) Proxy.newProxyInstance(
            Driver.class.getClassLoader(),
            new Class<?>[]{Driver.class},
            (proxy, method, args) -> {
                if ("verifyConnectivity".equals(method.getName()) || "close".equals(method.getName())) {
                    return null;
                }
                if ("session".equals(method.getName())) {
                    return session;
                }
                return unsupported(method.getName());
            }
        );
    }

    private static Object invokeTransactionCallback(Object callback, TransactionContext tx) throws Exception {
        return callback.getClass().getMethod("execute", TransactionContext.class).invoke(callback, tx);
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Unsupported proxy method: " + methodName);
    }
}
