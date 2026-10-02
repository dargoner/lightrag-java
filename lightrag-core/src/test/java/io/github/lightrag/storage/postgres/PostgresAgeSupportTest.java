package io.github.lightrag.storage.postgres;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresAgeSupportTest {
    @Test
    void derivesGraphNameFromWorkspaceLikeUpstream() {
        assertThat(PostgresAgeSupport.graphName(null)).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("")).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("   ")).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("default")).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("DEFAULT")).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName(" Default ")).isEqualTo("chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("ws1")).isEqualTo("ws1_chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("my.ws-a")).isEqualTo("my_ws_a_chunk_entity_relation");
        assertThat(PostgresAgeSupport.graphName("工作区")).isEqualTo("____chunk_entity_relation");
    }

    @Test
    void keepsWorkspaceCaseAndClipsGraphNameToPostgresNameLimit() {
        assertThat(PostgresAgeSupport.graphName("MyWorkspace")).isEqualTo("MyWorkspace_chunk_entity_relation");

        var workspace = "w".repeat(80);
        var graphName = PostgresAgeSupport.graphName(workspace);
        assertThat(graphName).isEqualTo(("w".repeat(80) + "_chunk_entity_relation").substring(0, 63));
        assertThat(graphName).hasSize(PostgresAgeSupport.PG_NAME_MAX_BYTES);
    }

    @Test
    void dollarQuotesWithTheFirstNonConflictingTag() {
        assertThat(PostgresAgeSupport.dollarQuote("abc")).isEqualTo("$AGE1$abc$AGE1$");
        assertThat(PostgresAgeSupport.dollarQuote("a $AGE1$ b")).isEqualTo("$AGE2$a $AGE1$ b$AGE2$");
        // The seam guard: appending the closing "$AGE1$" would complete a premature delimiter.
        assertThat(PostgresAgeSupport.dollarQuote("a$AGE1")).isEqualTo("$AGE2$a$AGE1$AGE2$");
        assertThat(PostgresAgeSupport.dollarQuote(null)).isEqualTo("$AGE1$$AGE1$");
    }

    @Test
    void rendersCypherPropertyMapWithJsonLiteralsAndBacktickKeys() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("name", "Alice \"Liddell\"\n");
        properties.put("weight", 0.75d);
        properties.put("count", 3);
        properties.put("active", true);
        properties.put("missing", null);
        properties.put("aliases", List.of("A", "阿丽丝"));
        properties.put("weird`key", "backtick");

        assertThat(PostgresAgeSupport.formatProperties(properties)).isEqualTo(
            "{`name`: \"Alice \\\"Liddell\\\"\\n\", `weight`: 0.75, `count`: 3, `active`: true, "
                + "`missing`: null, `aliases`: [\"A\", \"阿丽丝\"], `weird``key`: \"backtick\"}"
        );
        assertThat(PostgresAgeSupport.formatProperties(Map.of())).isEqualTo("{}");
    }

    @Test
    void encodesJsonStringsLikeEnsureAsciiFalse() {
        assertThat(PostgresAgeSupport.jsonString("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(PostgresAgeSupport.jsonString("line\nbreak\ttab\r\u0008\u000c")).isEqualTo("\"line\\nbreak\\ttab\\r\\b\\f\"");
        assertThat(PostgresAgeSupport.jsonString("bell\u0007")).isEqualTo("\"bell\\u0007\"");
        assertThat(PostgresAgeSupport.jsonString("中文 🔥")).isEqualTo("\"中文 🔥\"");
    }

    @Test
    void parsesOnlyFullThreeComponentVersions() {
        assertThat(PostgresAgeSupport.parseAgeVersion("1.7.0")).isEqualTo(new PostgresAgeSupport.AgeVersion(1, 7, 0));
        assertThat(PostgresAgeSupport.parseAgeVersion("01.7.0")).isEqualTo(new PostgresAgeSupport.AgeVersion(1, 7, 0));
        assertThat(PostgresAgeSupport.parseAgeVersion("1.7")).isNull();
        assertThat(PostgresAgeSupport.parseAgeVersion("1.7.0 ")).isNull();
        assertThat(PostgresAgeSupport.parseAgeVersion("1.7.0-beta")).isNull();
        assertThat(PostgresAgeSupport.parseAgeVersion("v1.7.0")).isNull();
        assertThat(PostgresAgeSupport.parseAgeVersion("")).isNull();
        assertThat(PostgresAgeSupport.parseAgeVersion(null)).isNull();
    }

    @Test
    void ordersVersionsNumerically() {
        assertThat(new PostgresAgeSupport.AgeVersion(1, 6, 0)).isLessThan(new PostgresAgeSupport.AgeVersion(1, 7, 0));
        assertThat(new PostgresAgeSupport.AgeVersion(1, 7, 0)).isLessThan(new PostgresAgeSupport.AgeVersion(1, 8, 0));
        assertThat(new PostgresAgeSupport.AgeVersion(1, 10, 0)).isGreaterThan(new PostgresAgeSupport.AgeVersion(1, 9, 9));
        assertThat(new PostgresAgeSupport.AgeVersion(2, 0, 0)).isGreaterThan(new PostgresAgeSupport.AgeVersion(1, 99, 99));
        assertThat(new PostgresAgeSupport.AgeVersion(1, 7, 0)).hasToString("1.7.0");
    }
}
