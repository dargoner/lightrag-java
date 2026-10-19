package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the relational-backend SQL shape without a live PostgreSQL: upserts always write the
 * {@code attributes} column (empty bags as {@code '{}'}), and loads decode the stored JSON, with
 * legacy {@code NULL} readings becoming an empty map.
 */
class PostgresGraphStoreAttributesJdbcTest {
    private static final PostgresStorageConfig CONFIG = new PostgresStorageConfig(
        "jdbc:postgresql://localhost:5432/lightrag",
        "lightrag",
        "secret",
        "rag",
        3,
        "lr_",
        PostgresVectorDialect.PGVECTOR
    );

    @Test
    void upsertEntityWritesTheAttributesColumnAndUpsertsIt() {
        var jdbc = new RecordingJdbc();
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        store.saveEntity(new EntityRecord(
            "alice", "Alice", "person", "Researcher", List.of(), List.of(), "",
            Map.of("role", "lead", "age", 30)
        ));

        var upsert = jdbc.lastStatementContaining("INSERT INTO \"rag\".\"lr_entities\"");
        assertThat(upsert.sql())
            .contains("(workspace_id, id, name, type, description, file_path, attributes)")
            .contains("attributes = EXCLUDED.attributes");
        assertThat(upsert.parameters()).containsEntry(7, "{\"age\":30,\"role\":\"lead\"}");
    }

    @Test
    void upsertEntityWritesAnEmptyJsonObjectForEmptyAttributes() {
        var jdbc = new RecordingJdbc();
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        store.saveEntity(new EntityRecord("alice", "Alice", "person", "Researcher", List.of(), List.of()));

        assertThat(jdbc.lastStatementContaining("INSERT INTO \"rag\".\"lr_entities\"").parameters())
            .containsEntry(7, "{}");
    }

    @Test
    void upsertRelationWritesTheAttributesColumnAndUpsertsIt() {
        var jdbc = new RecordingJdbc();
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        store.saveRelation(new RelationRecord(
            "alice|bob", "alice", "bob", "knows", "Alice knows Bob", 0.9d, "", "",
            Map.of("since", 1999)
        ));

        var upsert = jdbc.lastStatementContaining("INSERT INTO \"rag\".\"lr_relations\"");
        assertThat(upsert.sql())
            .contains("(workspace_id, id, src_id, tgt_id, keywords, description, weight, source_id, file_path, attributes)")
            .contains("attributes = EXCLUDED.attributes");
        assertThat(upsert.parameters()).containsEntry(10, "{\"since\":1999}");
    }

    @Test
    void loadEntityReadsTheAttributesColumn() {
        var jdbc = new RecordingJdbc();
        jdbc.entityRow(Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "file_path", "",
            "attributes", "{\"role\":\"lead\",\"age\":30}"
        ));
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        var entity = store.loadEntity("alice").orElseThrow();

        assertThat(entity.attributes()).containsEntry("role", "lead").containsEntry("age", 30L);
        assertThat(jdbc.statements()).anySatisfy(sql -> assertThat(sql)
            .contains("SELECT id, name, type, description, file_path, attributes"));
    }

    @Test
    void loadEntityTreatsNullAttributesAsAnEmptyMap() {
        var jdbc = new RecordingJdbc();
        var row = new LinkedHashMap<String, String>();
        row.put("id", "alice");
        row.put("name", "Alice");
        row.put("type", "person");
        row.put("description", "Researcher");
        row.put("file_path", "");
        row.put("attributes", null);
        jdbc.entityRow(row);
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        assertThat(store.loadEntity("alice").orElseThrow().attributes()).isEmpty();
    }

    @Test
    void loadRelationReadsTheAttributesColumn() {
        var jdbc = new RecordingJdbc();
        jdbc.relationRow(Map.of(
            "id", "alice|bob",
            "src_id", "alice",
            "tgt_id", "bob",
            "keywords", "knows",
            "description", "",
            "weight", 0.9d,
            "source_id", "",
            "file_path", "",
            "attributes", "{\"since\":1999}"
        ));
        var store = new PostgresGraphStore(JdbcConnectionAccess.forConnection(jdbc.connection()), CONFIG, "ws");

        var relation = store.loadRelation("alice|bob").orElseThrow();

        assertThat(relation.attributes()).containsEntry("since", 1999L);
        assertThat(jdbc.statements()).anySatisfy(sql -> assertThat(sql)
            .contains("SELECT id, src_id, tgt_id, keywords, description, weight, source_id, file_path, attributes"));
    }

    private record CapturedStatement(String sql, Map<Integer, Object> parameters) {
    }

    /**
     * JDBC stand-in: records prepared statements and bound parameters, answers write updates with
     * one affected row and serves at most one configured entity/relation row for loads.
     */
    private static final class RecordingJdbc {
        private final List<CapturedStatement> statements = new ArrayList<>();
        private Map<String, Object> row;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                PostgresGraphStoreAttributesJdbcTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit", "commit", "rollback", "close" -> null;
                    case "prepareStatement" -> statement((String) arguments[0]);
                    case "createArrayOf" -> {
                        var values = (Object[]) arguments[1];
                        yield java.util.Arrays.asList(values);
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "RecordingConnection";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private void entityRow(Map<String, ?> values) {
            row = copyRow(values);
        }

        private void relationRow(Map<String, ?> values) {
            row = copyRow(values);
        }

        private static Map<String, Object> copyRow(Map<String, ?> values) {
            var copy = new LinkedHashMap<String, Object>();
            values.forEach(copy::put);
            return copy;
        }

        private PreparedStatement statement(String sql) {
            var parameters = new LinkedHashMap<Integer, Object>();
            var captured = new CapturedStatement(sql, parameters);
            statements.add(captured);
            var resultSet = select(sql) ? singleRow() : emptyResultSet();
            return (PreparedStatement) Proxy.newProxyInstance(
                PostgresGraphStoreAttributesJdbcTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setString", "setDouble", "setInt", "setObject" -> {
                        parameters.put((Integer) arguments[0], arguments[1]);
                        yield null;
                    }
                    case "setArray" -> {
                        parameters.put((Integer) arguments[0], arguments[1]);
                        yield null;
                    }
                    case "executeUpdate" -> 1;
                    case "executeQuery" -> resultSet;
                    case "executeBatch" -> new int[0];
                    case "addBatch", "clearBatch", "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "RecordingStatement[" + sql + "]";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private boolean select(String sql) {
            return row != null
                && (sql.contains("SELECT id, name, type, description, file_path, attributes")
                    || sql.contains("SELECT id, src_id, tgt_id, keywords, description, weight, source_id, file_path, attributes"));
        }

        private ResultSet singleRow() {
            var values = row;
            var index = new int[] {-1};
            return (ResultSet) Proxy.newProxyInstance(
                PostgresGraphStoreAttributesJdbcTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> ++index[0] == 0;
                    case "getString" -> {
                        Object value = values.get((String) arguments[0]);
                        yield value == null ? null : String.valueOf(value);
                    }
                    case "getDouble" -> values.get((String) arguments[0]) instanceof Number number
                        ? number.doubleValue()
                        : 0.0d;
                    case "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "SingleRowResultSet";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private ResultSet emptyResultSet() {
            return (ResultSet) Proxy.newProxyInstance(
                PostgresGraphStoreAttributesJdbcTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "EmptyResultSet";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private CapturedStatement lastStatementContaining(String fragment) {
            return statements.stream()
                .filter(statement -> statement.sql().contains(fragment))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No prepared statement containing: " + fragment));
        }

        private List<String> statements() {
            return statements.stream().map(CapturedStatement::sql).toList();
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == double.class) {
                return 0.0d;
            }
            if (returnType == float.class) {
                return 0.0f;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == char.class) {
                return (char) 0;
            }
            return 0;
        }
    }
}
