package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresAgeCypherSupportTest {
    @Test
    void returnItemsIsEmptyWhenTheStatementHasNoReturnClause() {
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n:base)\nDETACH DELETE n")).isEmpty();
        assertThat(PostgresAgeCypherSupport.returnItems("// only a comment\n")).isEmpty();
        assertThat(PostgresAgeCypherSupport.returnItems("/* block RETURN comment */ MATCH (n) DELETE n")).isEmpty();
    }

    @Test
    void returnItemsSplitsTopLevelCommasOnly() {
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n:base) RETURN n"))
            .containsExactly("n");
        assertThat(PostgresAgeCypherSupport.returnItems("RETURN n.entity_id, r.weight"))
            .containsExactly("n.entity_id", "r.weight");
        assertThat(PostgresAgeCypherSupport.returnItems(
            "RETURN collect(n.name), size([x IN n.aliases WHERE x <> ',']) AS alias_count"
        )).containsExactly(
            "collect(n.name)",
            "size([x IN n.aliases WHERE x <> ',']) AS alias_count"
        );
        assertThat(PostgresAgeCypherSupport.returnItems(
            "RETURN {name: n.name, type: n.entity_type} AS info, [(n)-[:DIRECTED]->(m) | m.entity_id] AS ns"
        )).containsExactly(
            "{name: n.name, type: n.entity_type} AS info",
            "[(n)-[:DIRECTED]->(m) | m.entity_id] AS ns"
        );
    }

    @Test
    void returnItemsStopsAtTrailingClauses() {
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN n ORDER BY n.name SKIP 1 LIMIT 2"))
            .containsExactly("n");
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN n UNION MATCH (m) RETURN m"))
            .containsExactly("n");
    }

    @Test
    void returnItemsSkipsDistinctAndNestedReturns() {
        assertThat(PostgresAgeCypherSupport.returnItems("RETURN DISTINCT n.entity_type AS type"))
            .containsExactly("n.entity_type AS type");
        assertThat(PostgresAgeCypherSupport.returnItems("CALL { MATCH (m:base) RETURN m } RETURN count(*) AS total"))
            .containsExactly("count(*) AS total");
    }

    @Test
    void returnItemsIgnoresReturnInsideStringsCommentsAndPropertyAccess() {
        assertThat(PostgresAgeCypherSupport.returnItems("RETURN 'RETURN', n"))
            .containsExactly("'RETURN'", "n");
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN n // RETURN x\n"))
            .containsExactly("n");
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n) WHERE n.return = 1 RETURN n.return"))
            .containsExactly("n.return");
        assertThat(PostgresAgeCypherSupport.returnItems("MATCH (n) WHERE n.x = $return RETURN n"))
            .containsExactly("n");
    }

    @Test
    void returnItemsRejectsStarAndEmptyItems() {
        assertThatThrownBy(() -> PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN *"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("RETURN *");
        assertThatThrownBy(() -> PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN n, *"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("RETURN *");
        assertThatThrownBy(() -> PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN n,"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("empty RETURN item");
        assertThatThrownBy(() -> PostgresAgeCypherSupport.returnItems("MATCH (n) RETURN"))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("empty RETURN item");
    }

    @Test
    void columnNamesPreferAliases() {
        assertThat(PostgresAgeCypherSupport.columnNames(List.of(
            "n.name AS name",
            "count(*) AS total",
            "n.x as lower",
            "n.entity_id",
            "n AS `node alias`",
            "size([a IN n.aliases WHERE a = 'AS']) AS cnt"
        ))).containsExactly(
            "name",
            "total",
            "lower",
            "n.entity_id",
            "node alias",
            "cnt"
        );
    }

    @Test
    void columnDefinitionListDeclaresPositionalAgtypeColumns() {
        assertThat(PostgresAgeCypherSupport.columnDefinitionList(0))
            .isEqualTo("value ag_catalog.agtype");
        assertThat(PostgresAgeCypherSupport.columnDefinitionList(2))
            .isEqualTo("c1 ag_catalog.agtype, c2 ag_catalog.agtype");
    }

    @Test
    void toJavaValueParsesScalarsCompositesAndGraphElements() {
        assertThat(PostgresAgeCypherSupport.toJavaValue(null)).isNull();
        assertThat(PostgresAgeCypherSupport.toJavaValue("\"plain string\"")).isEqualTo("plain string");
        assertThat(PostgresAgeCypherSupport.toJavaValue("42")).isEqualTo(42);
        assertThat(PostgresAgeCypherSupport.toJavaValue("1.5")).isEqualTo(1.5d);
        assertThat(PostgresAgeCypherSupport.toJavaValue("true")).isEqualTo(true);
        assertThat(PostgresAgeCypherSupport.toJavaValue("[1, \"two\", {\"a\": null}]"))
            .isEqualTo(List.of(1, "two", java.util.Collections.singletonMap("a", null)));
    }

    @Test
    void toJavaValueStripsGraphValueSuffixes() {
        assertThat(PostgresAgeCypherSupport.toJavaValue(
            "{\"id\": 1, \"label\": \"base\", \"properties\": {\"name\": \"Alice\"}}::vertex"
        )).isEqualTo(Map.of("id", 1, "label", "base", "properties", Map.of("name", "Alice")));
        assertThat(PostgresAgeCypherSupport.toJavaValue(
            "{\"id\": 2, \"label\": \"DIRECTED\", \"start_id\": 1, \"end_id\": 3}::edge"
        )).isEqualTo(Map.of("id", 2, "label", "DIRECTED", "start_id", 1, "end_id", 3));
        assertThat(PostgresAgeCypherSupport.toJavaValue(
            "[{\"id\": 1}::vertex, {\"id\": 2}::edge, {\"id\": 3}::vertex]::path"
        )).isEqualTo(List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));

        // A suffix-like sequence inside a JSON string stays untouched.
        assertThat(PostgresAgeCypherSupport.toJavaValue("[\"::vertex\"]"))
            .isEqualTo(List.of("::vertex"));
    }

    @Test
    void toJavaValueFallsBackToRawTextForNonJson() {
        assertThat(PostgresAgeCypherSupport.toJavaValue("not json at all")).isEqualTo("not json at all");
    }
}
