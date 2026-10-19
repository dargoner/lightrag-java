package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Apache AGE property contract for the materialized attribute bag without a database:
 * vertex and inline edge property maps carry the canonical JSON string, and property rows written
 * before the bag existed (missing) or with a malformed bag decode to an empty map.
 */
class PostgresAgeGraphStoreAttributesTest {
    private static final Method ENTITY_PROPERTIES = privateMethod("entityProperties", EntityRecord.class);
    private static final Method RELATION_PROPERTIES = privateMethod("relationProperties", RelationRecord.class);
    private static final Method TO_ENTITY = privateMethod("toEntityRecord", String.class);
    private static final Method TO_RELATION = privateMethod("toRelationRecord", String.class);

    @Test
    void entityPropertiesCarryTheCanonicalAttributesJson() throws Exception {
        var properties = entityProperties(new EntityRecord(
            "alice",
            "Alice",
            "person",
            "Researcher",
            List.of(),
            List.of(),
            "",
            Map.of("role", "lead", "age", 30)
        ));

        assertThat(properties.get("attributes")).isEqualTo("{\"age\":30,\"role\":\"lead\"}");
    }

    @Test
    void emptyEntityAttributesSerializeAsAnEmptyJsonObject() throws Exception {
        var properties = entityProperties(new EntityRecord(
            "alice", "Alice", "person", "Researcher", List.of(), List.of()));

        assertThat(properties.get("attributes")).isEqualTo("{}");
    }

    @Test
    void relationPropertiesCarryTheAttributesJsonIntoTheInlineEdgeMap() throws Exception {
        var properties = relationProperties(new RelationRecord(
            "alice|bob",
            "alice",
            "bob",
            "knows",
            "Alice knows Bob",
            0.9d,
            "",
            "",
            Map.of("since", 1999)
        ));

        assertThat(properties.get("attributes")).isEqualTo("{\"since\":1999}");
        // Edges only persist properties inlined in CREATE, so the bag must survive formatting.
        assertThat(PostgresAgeSupport.formatProperties(properties))
            .contains("`attributes`: \"{\\\"since\\\":1999}\"");
    }

    @Test
    void entityRowReadsBackItsAttributes() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(graphStore(),
            "{\"entity_id\": \"alice\", \"name\": \"Alice\", \"attributes\": \"{\\\"role\\\":\\\"lead\\\"}\"}");

        assertThat(entity.attributes()).containsEntry("role", "lead");
    }

    @Test
    void entityRowWithoutAttributesReadsAsEmptyMap() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(graphStore(),
            "{\"entity_id\": \"alice\", \"name\": \"Alice\"}");

        assertThat(entity.attributes()).isEmpty();
    }

    @Test
    void malformedEntityAttributesReadAsEmptyMapRatherThanFailingTheRow() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(graphStore(),
            "{\"entity_id\": \"alice\", \"name\": \"Alice\", \"attributes\": \"{not json\"}");

        assertThat(entity.attributes()).isEmpty();
    }

    @Test
    void relationRowReadsBackItsAttributes() throws Exception {
        var relation = (RelationRecord) TO_RELATION.invoke(graphStore(),
            "{\"relation_id\": \"alice|bob\", \"src_id\": \"alice\", \"tgt_id\": \"bob\","
                + " \"attributes\": \"{\\\"since\\\":1999}\"}");

        assertThat(relation.attributes()).containsEntry("since", 1999L);
    }

    @Test
    void relationRowWithoutAttributesReadsAsEmptyMap() throws Exception {
        var relation = (RelationRecord) TO_RELATION.invoke(graphStore(),
            "{\"relation_id\": \"alice|bob\", \"src_id\": \"alice\", \"tgt_id\": \"bob\"}");

        assertThat(relation.attributes()).isEmpty();
    }

    private static PostgresAgeGraphStore graphStore() {
        return new PostgresAgeGraphStore(JdbcConnectionAccess.forDataSource(new UnusedDataSource()), "ws");
    }

    private static Method privateMethod(String name, Class<?>... parameterTypes) {
        try {
            var method = PostgresAgeGraphStore.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Missing PostgresAgeGraphStore#" + name, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entityProperties(EntityRecord entity) throws Exception {
        return (Map<String, Object>) ENTITY_PROPERTIES.invoke(null, entity);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> relationProperties(RelationRecord relation) throws Exception {
        return (Map<String, Object>) RELATION_PROPERTIES.invoke(null, relation);
    }

    /** Only the identity of this source matters: these private projections never open a connection. */
    private static final class UnusedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("not used");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
