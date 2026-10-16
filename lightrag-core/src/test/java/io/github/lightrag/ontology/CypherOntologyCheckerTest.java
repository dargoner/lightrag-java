package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CypherOntologyCheckerTest {

    private static final PhysicalGraphProfile NEO4J_PROFILE =
        new PhysicalGraphProfile(Set.of("Entity"), Set.of("RELATION"), "type");

    private static final CypherOntologyAllowlist ALLOWLIST =
        CypherOntologyAllowlist.of(List.of("Person", "Organization"), List.of("works at"));

    @Test
    void acceptsValidQueryWithLiteralTypeValue() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type = 'Person' RETURN n.name");

        assertThat(result.ok()).isTrue();
        assertThat(result.unknownLabels()).isEmpty();
        assertThat(result.unknownRelTypes()).isEmpty();
        assertThat(result.unknownTypeValues()).isEmpty();
        assertThat(result.unverified()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void acceptsParameterTypeValueCaseInsensitively() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = $type RETURN n.name",
            Map.of("type", "person"),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsUnknownLiteralTypeValue() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type = 'Employee' RETURN n.name");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsUnknownValueInsideInList() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type IN ['Person', 'Employee'] RETURN n.name");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void acceptsParameterListForIn() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type IN $types RETURN n.name",
            Map.of("types", List.of("Person", "Organization")),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsUnknownElementInsideParameterList() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type IN $types RETURN n.name",
            Map.of("types", List.of("Person", "Employee")),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void flagsMissingParameter() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = $type RETURN n.name",
            Map.of(),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("parameter '$type' is not provided");
    }

    @Test
    void flagsNullParameter() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = $type RETURN n.name",
            Collections.singletonMap("type", null),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("parameter '$type' is null");
    }

    @Test
    void flagsNonStringParameter() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = $type RETURN n.name",
            Map.of("type", 42),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified())
            .contains("type condition is not statically verifiable: parameter '$type' value is not a string");
    }

    @Test
    void flagsNonStringElementInParameterList() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type IN $types RETURN n.name",
            Map.of("types", List.of("Person", 42)),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified())
            .contains("type condition is not statically verifiable: parameter '$types' list element is not a string");
    }

    @Test
    void rejectsUnsupportedTypeComparisonOperators() {
        CypherOntologyChecker.CheckResult notEquals = check(
            "MATCH (n:Entity) WHERE n.type <> 'Person' RETURN n.name");
        CypherOntologyChecker.CheckResult regex = check(
            "MATCH (n:Entity) WHERE n.type =~ 'Per.*' RETURN n.name");

        assertThat(notEquals.ok()).isFalse();
        assertThat(notEquals.unverified())
            .contains("type condition is not statically verifiable: unsupported operator '<>'");
        assertThat(regex.ok()).isFalse();
        assertThat(regex.unverified())
            .contains("type condition is not statically verifiable: unsupported operator '=~'");
    }

    @Test
    void acceptsReversedEquality() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE 'Person' = n.type RETURN n.name");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsReversedEqualityWithUnknownValue() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE 'Employee' = n.type RETURN n.name");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsUnknownNodeLabel() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Person) WHERE n.type = 'Person' RETURN n.name");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Person");
    }

    @Test
    void rejectsUnknownRelationshipType() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (a:Entity)-[r:FRIEND]->(b:Entity) RETURN r");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownRelTypes()).containsExactly("FRIEND");
    }

    @Test
    void acceptsKnownRelationshipTypePattern() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (a:Entity)-[r:RELATION]->(b:Entity) RETURN r");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsWriteKeywordAndApoc() {
        CypherOntologyChecker.CheckResult merge = check(
            "MERGE (n:Entity {name: 'x'}) RETURN n");
        CypherOntologyChecker.CheckResult apoc = check(
            "MATCH (n:Entity) WITH n CALL apoc.periodic.commit('x') RETURN n");

        assertThat(merge.ok()).isFalse();
        assertThat(merge.unverified()).contains("write or unsupported keyword: MERGE");
        assertThat(apoc.ok()).isFalse();
        assertThat(apoc.unverified())
            .contains("write or unsupported keyword: CALL")
            .contains("write or unsupported keyword: APOC");
    }

    @Test
    void rejectsQueryWithoutReturn() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type = 'Person'");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("query must contain a RETURN clause");
    }

    @Test
    void treatsNullAndBlankQueryAsMissingReturn() {
        CypherOntologyChecker.CheckResult nullResult = CypherOntologyChecker.check(
            null, Map.of(), ALLOWLIST, NEO4J_PROFILE);
        CypherOntologyChecker.CheckResult blankResult = CypherOntologyChecker.check(
            "   \n  ", Map.of(), ALLOWLIST, NEO4J_PROFILE);

        assertThat(nullResult.ok()).isFalse();
        assertThat(nullResult.unverified()).contains("query must contain a RETURN clause");
        assertThat(blankResult.ok()).isFalse();
        assertThat(blankResult.unverified()).contains("query must contain a RETURN clause");
    }

    @Test
    void ignoresWriteKeywordsAndLabelsInsideStringLiterals() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.name = 'CREATE MERGE :Person' RETURN n.name");

        assertThat(result.ok()).isTrue();
        assertThat(result.unknownLabels()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void ignoresWriteKeywordsAndLabelsInsideComments() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity)\n// DELETE SET\nWHERE n.name = 'x' /* CREATE :Person */\nRETURN n.name");

        assertThat(result.ok()).isTrue();
        assertThat(result.unknownLabels()).isEmpty();
    }

    @Test
    void skipsMapLiteralKeys() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity {type: 'Person'}) RETURN n");

        assertThat(result.ok()).isTrue();
        assertThat(result.unknownLabels()).isEmpty();
    }

    @Test
    void flagsAmbiguousLabelOutsidePatternWithWarning() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n:Person RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Person");
        assertThat(result.warnings())
            .contains("label ':Person' appears outside a node or relationship pattern");
    }

    @Test
    void acceptsBacktickNodeLabel() {
        CypherOntologyChecker.CheckResult result = check("MATCH (n:`Entity`) RETURN n");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void usesProfileTypePropertyNameForValueChecks() {
        PhysicalGraphProfile ageProfile =
            new PhysicalGraphProfile(Set.of("base"), Set.of("DIRECTED"), "entity_type");

        CypherOntologyChecker.CheckResult ageProperty = CypherOntologyChecker.check(
            "MATCH (n:base) WHERE n.entity_type = 'Employee' RETURN n",
            Map.of(),
            ALLOWLIST,
            ageProfile
        );
        CypherOntologyChecker.CheckResult otherProperty = CypherOntologyChecker.check(
            "MATCH (n:base) WHERE n.type = 'Employee' RETURN n",
            Map.of(),
            ALLOWLIST,
            ageProfile
        );

        assertThat(ageProperty.ok()).isFalse();
        assertThat(ageProperty.unknownTypeValues()).containsExactly("Employee");
        assertThat(otherProperty.unverified()).isEmpty();
        assertThat(otherProperty.unknownTypeValues()).isEmpty();
    }

    @Test
    void allowsFallbackTypeEvenWhenNotDeclared() {
        CypherOntologyChecker.CheckResult declaredFallback = check(
            "MATCH (n:Entity) WHERE n.type = 'Other' RETURN n");
        CypherOntologyChecker.CheckResult emptyAllowlistFallback = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = 'Other' RETURN n",
            Map.of(),
            CypherOntologyAllowlist.empty(),
            NEO4J_PROFILE
        );
        CypherOntologyChecker.CheckResult emptyAllowlistDeclared = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = 'Person' RETURN n",
            Map.of(),
            CypherOntologyAllowlist.empty(),
            NEO4J_PROFILE
        );

        assertThat(declaredFallback.ok()).isTrue();
        assertThat(emptyAllowlistFallback.ok()).isTrue();
        assertThat(emptyAllowlistDeclared.ok()).isFalse();
        assertThat(emptyAllowlistDeclared.unknownTypeValues()).containsExactly("Person");
    }

    private static CypherOntologyChecker.CheckResult check(String cypher) {
        return CypherOntologyChecker.check(cypher, Map.of(), ALLOWLIST, NEO4J_PROFILE);
    }
}
