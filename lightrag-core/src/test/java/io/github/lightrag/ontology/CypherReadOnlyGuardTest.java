package io.github.lightrag.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CypherReadOnlyGuardTest {

    @Test
    void acceptsParameterizedReadQuery() {
        CypherReadOnlyGuard.Result result =
            CypherReadOnlyGuard.check(
                "MATCH (n:Entity) WHERE n.workspaceId = $workspaceId "
                    + "AND n.type = $type RETURN n.name LIMIT 10");

        assertThat(result.ok()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsWriteStatementsWithoutOntologyContext() {
        assertRejected("CREATE (n:Entity {name: 'x'})");
        assertRejected("MATCH (n:Entity) DETACH DELETE n");
        assertRejected("MATCH (n:Entity) SET n.name = 'x' RETURN n");
        assertRejected("MERGE (n:Entity {name: 'x'}) RETURN n");
        assertRejected("CALL apoc.periodic.iterate('x', 'y', {})");
    }

    @Test
    void ignoresKeywordsInsideStringsCommentsAndQuotedIdentifiers() {
        CypherReadOnlyGuard.Result result =
            CypherReadOnlyGuard.check(
                "// DELETE is only mentioned here\n"
                    + "MATCH (n:Entity) WHERE n.description = 'create and delete text' "
                    + "RETURN n.`set`");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsUnicodeEscapesOutsideTokens() {
        assertRejected("MATCH (n:Entity) RETURN n\nCRE\\u0041TE (x:Entity)");
    }

    @Test
    void rejectsApocEvenWhenWrittenWithSpaces() {
        assertRejected("MATCH (n:Entity) RETURN apoc . load.csv('x')");
    }

    private static void assertRejected(String cypher) {
        CypherReadOnlyGuard.Result result = CypherReadOnlyGuard.check(cypher);

        assertThat(result.ok()).isFalse();
        assertThat(result.violations()).isNotEmpty();
    }
}
