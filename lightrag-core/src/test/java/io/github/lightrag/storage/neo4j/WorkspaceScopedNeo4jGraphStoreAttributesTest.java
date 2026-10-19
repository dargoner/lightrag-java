package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Neo4j/Memgraph attribute contract without a server: every single and batch write binds
 * the canonical attribute JSON as a Cypher parameter, and row maps decode the stored JSON back
 * (missing or blank readings become an empty map).
 */
class WorkspaceScopedNeo4jGraphStoreAttributesTest {
    private static final Method TO_ENTITY = privateMethod("toEntity", Record.class);
    private static final Method TO_RELATION = privateMethod("toRelation", Record.class);

    @Test
    void saveEntitySetsTheAttributesParameter() {
        var recording = new RecordingDriver();
        var store = recording.store();

        store.saveEntity(new EntityRecord(
            "alice", "Alice", "person", "Researcher", List.of(), List.of(), "",
            Map.of("role", "lead", "age", 30)
        ));

        var write = recording.lastQueryContaining("entity.attributes = $attributes");
        assertThat(write.query()).contains("entity.attributes = $attributes");
        assertThat(write.parameters()).containsEntry("attributes", "{\"age\":30,\"role\":\"lead\"}");
    }

    @Test
    void saveEntityWritesAnEmptyJsonObjectForEmptyAttributes() {
        var recording = new RecordingDriver();
        var store = recording.store();

        store.saveEntity(new EntityRecord("alice", "Alice", "person", "Researcher", List.of(), List.of()));

        var write = recording.lastQueryContaining("entity.attributes = $attributes");
        assertThat(write.parameters()).containsEntry("attributes", "{}");
    }

    @Test
    void saveEntitiesBindsTheAttributesJsonPerRow() {
        var recording = new RecordingDriver();
        var store = recording.store();

        store.saveEntities(List.of(
            new EntityRecord("alice", "Alice", "person", "", List.of(), List.of(), "",
                Map.of("role", "lead")),
            new EntityRecord("bob", "Bob", "person", "", List.of(), List.of())
        ));

        var write = recording.lastQueryContaining("entity.attributes = row.attributes");
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) write.parameters().get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("attributes", "{\"role\":\"lead\"}");
        assertThat(rows.get(1)).containsEntry("attributes", "{}");
    }

    @Test
    void saveRelationSetsTheAttributesParameterAndSeedsPlaceholdersWithAnEmptyBag() {
        var recording = new RecordingDriver();
        var store = recording.store();

        store.saveRelation(new RelationRecord(
            "alice|bob", "alice", "bob", "knows", "Alice knows Bob", 0.9d, "", "",
            Map.of("since", 1999)
        ));

        var write = recording.lastQueryContaining("relation.attributes = $attributes");
        assertThat(write.query())
            .contains("relation.attributes = $attributes")
            .contains("source.attributes = '{}'")
            .contains("target.attributes = '{}'");
        assertThat(write.parameters()).containsEntry("attributes", "{\"since\":1999}");
    }

    @Test
    void saveRelationsBindsTheAttributesJsonPerRow() {
        var recording = new RecordingDriver();
        var store = recording.store();

        store.saveRelations(List.of(new RelationRecord(
            "alice|bob", "alice", "bob", "knows", "", 0.9d, "", "",
            Map.of("since", 1999)
        )));

        var write = recording.lastQueryContaining("relation.attributes = row.attributes");
        assertThat(write.query()).contains("relation.attributes = row.attributes");
        var endpointWrite = recording.lastQueryContaining("source.attributes = '{}'");
        assertThat(endpointWrite.query())
            .contains("source.attributes = '{}'")
            .contains("target.attributes = '{}'")
            .contains("$allRows");
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) write.parameters().get("lastRows");
        assertThat(rows).singleElement().satisfies(row ->
            assertThat(row).containsEntry("attributes", "{\"since\":1999}"));
    }

    @Test
    void entityRowMapReadsTheAttributesJson() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(null, record(Map.of("entity", Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "filePath", "",
            "attributes", "{\"role\":\"lead\",\"age\":30}"
        ))));

        assertThat(entity.attributes()).containsEntry("role", "lead").containsEntry("age", 30L);
    }

    @Test
    void entityRowMapWithoutAttributesReadsAsEmptyMap() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(null, record(Map.of("entity", Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", ""
        ))));

        assertThat(entity.attributes()).isEmpty();
    }

    @Test
    void malformedEntityAttributesReadAsEmptyMap() throws Exception {
        var entity = (EntityRecord) TO_ENTITY.invoke(null, record(Map.of("entity", Map.of(
            "id", "alice",
            "name", "Alice",
            "attributes", "{not json"
        ))));

        assertThat(entity.attributes()).isEmpty();
    }

    @Test
    void relationRowMapReadsTheAttributesJson() throws Exception {
        var relation = (RelationRecord) TO_RELATION.invoke(null, record(Map.of("relation", Map.of(
            "relation_id", "alice|bob",
            "src_id", "alice",
            "tgt_id", "bob",
            "keywords", "knows",
            "description", "",
            "weight", 0.9d,
            "file_path", "",
            "attributes", "{\"since\":1999}"
        ))));

        assertThat(relation.attributes()).containsEntry("since", 1999L);
    }

    @Test
    void relationRowMapWithoutAttributesReadsAsEmptyMap() throws Exception {
        var relation = (RelationRecord) TO_RELATION.invoke(null, record(Map.of("relation", Map.of(
            "relation_id", "alice|bob",
            "src_id", "alice",
            "tgt_id", "bob"
        ))));

        assertThat(relation.attributes()).isEmpty();
    }

    private static Method privateMethod(String name, Class<?>... parameterTypes) {
        try {
            var method = WorkspaceScopedNeo4jGraphStore.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Missing WorkspaceScopedNeo4jGraphStore#" + name, exception);
        }
    }

    /**
     * Builds a driver {@link Record} whose fields are plain values; the store's row mappers only
     * read named fields, so a proxy covers that surface without a server.
     */
    private static Record record(Map<String, Object> fields) {
        var values = new LinkedHashMap<String, Value>();
        fields.forEach((key, value) -> values.put(key, Values.value(value)));
        var keys = List.copyOf(values.keySet());
        var orderedValues = keys.stream().map(values::get).toList();
        return (Record) Proxy.newProxyInstance(
            WorkspaceScopedNeo4jGraphStoreAttributesTest.class.getClassLoader(),
            new Class<?>[] {Record.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "get" -> {
                    if (arguments[0] instanceof String key) {
                        yield values.getOrDefault(key, Values.NULL);
                    }
                    yield orderedValues.get((Integer) arguments[0]);
                }
                case "keys" -> keys;
                case "values" -> orderedValues;
                case "index" -> keys.indexOf((String) arguments[0]);
                case "containsKey" -> values.containsKey((String) arguments[0]);
                case "size" -> values.size();
                case "fields" -> List.of();
                case "asMap" -> values.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().asObject()));
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Record" + fields.keySet();
                default -> defaultValue(method.getReturnType());
            }
        );
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

    private record CapturedQuery(String query, Map<String, Object> parameters) {
    }

    /**
     * Driver stand-in: the constructor's connectivity check and bootstrap succeed, and every
     * statement run through a write transaction is captured with its bound parameters.
     */
    private static final class RecordingDriver {
        private final List<CapturedQuery> queries = new ArrayList<>();
        private final Result result = result();

        private WorkspaceScopedNeo4jGraphStore store() {
            return new WorkspaceScopedNeo4jGraphStore(driver(), "neo4j", new WorkspaceScope("ws"));
        }

        private CapturedQuery lastQueryContaining(String fragment) {
            return queries.stream()
                .filter(query -> query.query().contains(fragment))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No captured query containing: " + fragment));
        }

        private Driver driver() {
            var session = session();
            return (Driver) Proxy.newProxyInstance(
                WorkspaceScopedNeo4jGraphStoreAttributesTest.class.getClassLoader(),
                new Class<?>[] {Driver.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "verifyConnectivity", "close" -> null;
                    case "session" -> session;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "RecordingDriver";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private Session session() {
            var transaction = transaction();
            return (Session) Proxy.newProxyInstance(
                WorkspaceScopedNeo4jGraphStoreAttributesTest.class.getClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "run" -> {
                        capture((String) arguments[0], arguments.length > 1 ? arguments[1] : null);
                        yield result;
                    }
                    case "executeWrite", "executeRead" -> {
                        @SuppressWarnings("rawtypes")
                        var callback = (TransactionCallback) arguments[0];
                        yield callback.execute(transaction);
                    }
                    case "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "RecordingSession";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private TransactionContext transaction() {
            return (TransactionContext) Proxy.newProxyInstance(
                WorkspaceScopedNeo4jGraphStoreAttributesTest.class.getClassLoader(),
                new Class<?>[] {TransactionContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "run" -> {
                        capture((String) arguments[0], arguments.length > 1 ? arguments[1] : null);
                        yield result;
                    }
                    case "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "RecordingTransaction";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private Result result() {
            return (Result) Proxy.newProxyInstance(
                WorkspaceScopedNeo4jGraphStoreAttributesTest.class.getClassLoader(),
                new Class<?>[] {Result.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasNext" -> false;
                    case "list" -> List.of();
                    case "consume" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "EmptyResult";
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        @SuppressWarnings("unchecked")
        private void capture(String query, Object parameters) {
            Map<String, Object> bound;
            if (parameters == null) {
                bound = Map.of();
            } else if (parameters instanceof Value value) {
                bound = value.asMap();
            } else {
                bound = (Map<String, Object>) parameters;
            }
            queries.add(new CapturedQuery(query, bound));
        }
    }
}
