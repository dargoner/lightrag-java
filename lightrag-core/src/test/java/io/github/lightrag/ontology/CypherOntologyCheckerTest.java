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

    @Test
    void rejectsWriteKeywordHiddenBehindBacktickCommentMarkers() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (`n//`:Entity) CREATE (:Entity)\nRETURN 1");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void rejectsUnknownTypeValueInPatternPropertyMap() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity {type: 'Employee'}) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void acceptsKnownTypeValueInPatternPropertyMap() {
        CypherOntologyChecker.CheckResult literal = check(
            "MATCH (n:Entity {type: 'Person'}) RETURN n");
        CypherOntologyChecker.CheckResult parameter = CypherOntologyChecker.check(
            "MATCH (n:Entity {type: $type}) RETURN n",
            Map.of("type", "person"),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(literal.ok()).isTrue();
        assertThat(parameter.ok()).isTrue();
    }

    @Test
    void rejectsCompoundTypeValueInPatternPropertyMap() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity {type: 'Person' + 'nel'}) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " map property value");
    }

    @Test
    void verifiesBacktickedVariableTypeAccess() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE `n`.type = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void verifiesSpacedAndSubscriptTypeAccess() {
        CypherOntologyChecker.CheckResult spaced = check(
            "MATCH (n:Entity) WHERE n . type = 'Employee' RETURN n");
        CypherOntologyChecker.CheckResult subscript = check(
            "MATCH (n:Entity) WHERE n['type'] = 'Employee' RETURN n");

        assertThat(spaced.ok()).isFalse();
        assertThat(spaced.unknownTypeValues()).containsExactly("Employee");
        assertThat(subscript.ok()).isFalse();
        assertThat(subscript.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void verifiesCaseNormalizedTypeAccessWithLowerWrapper() {
        CypherOntologyChecker.CheckResult rejected = check(
            "MATCH (n:Entity) WHERE toLower(n.type) = 'employee' RETURN n");
        CypherOntologyChecker.CheckResult accepted = check(
            "MATCH (n:Entity) WHERE toLower(n.type) = 'person' RETURN n");

        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.unknownTypeValues()).containsExactly("employee");
        assertThat(accepted.ok()).isTrue();
    }

    @Test
    void rejectsUnsupportedWrapperFunctionOnTypeAccess() {
        CypherOntologyChecker.CheckResult single = check(
            "MATCH (n:Entity) WHERE toString(n.type) = 'Person' RETURN n");
        CypherOntologyChecker.CheckResult multi = check(
            "MATCH (n:Entity) WHERE substring(n.type, 1, 2) = 'Pe' RETURN n");

        assertThat(single.ok()).isFalse();
        assertThat(single.unverified()).contains(
            "type condition is not statically verifiable: unsupported function 'tostring'");
        assertThat(multi.ok()).isFalse();
        assertThat(multi.unverified()).contains(
            "type condition is not statically verifiable: unsupported function 'substring'");
    }

    @Test
    void keepsPlainAndGroupedTypeProjectionsValid() {
        CypherOntologyChecker.CheckResult projection = check(
            "MATCH (n:Entity) WHERE n.name = 'x' RETURN n.type");
        CypherOntologyChecker.CheckResult wrappedProjection = check(
            "MATCH (n:Entity) RETURN toLower(n.type)");
        CypherOntologyChecker.CheckResult grouped = check(
            "MATCH (n:Entity) WHERE (n.type = 'Person') RETURN n");

        assertThat(projection.ok()).isTrue();
        assertThat(wrappedProjection.ok()).isTrue();
        assertThat(grouped.ok()).isTrue();
    }

    @Test
    void rejectsCompoundTypeValueExpressions() {
        CypherOntologyChecker.CheckResult concatenated = check(
            "MATCH (n:Entity) WHERE n.type = 'Person' + 'nel' RETURN n");
        CypherOntologyChecker.CheckResult inConcatenated = check(
            "MATCH (n:Entity) WHERE n.type IN ['Person'] + ['Employee'] RETURN n");

        assertThat(concatenated.ok()).isFalse();
        assertThat(concatenated.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " right-hand side");
        assertThat(inConcatenated.ok()).isFalse();
        assertThat(inConcatenated.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the IN list");
    }

    @Test
    void rejectsCompoundParameterExpressions() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type = $type + 'x' RETURN n",
            Map.of("type", "Person"),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " right-hand side");
    }

    @Test
    void rejectsUnknownRelationshipTypeInUnion() {
        CypherOntologyChecker.CheckResult bare = check(
            "MATCH ()-[r:RELATION|FORBIDDEN]->() RETURN r");
        CypherOntologyChecker.CheckResult colonPrefixed = check(
            "MATCH ()-[r:RELATION|:FORBIDDEN]->() RETURN r");

        assertThat(bare.ok()).isFalse();
        assertThat(bare.unknownRelTypes()).containsExactly("FORBIDDEN");
        assertThat(colonPrefixed.ok()).isFalse();
        assertThat(colonPrefixed.unknownRelTypes()).containsExactly("FORBIDDEN");
    }

    @Test
    void rejectsUnicodeNodeLabel() {
        CypherOntologyChecker.CheckResult result = check("MATCH (n:未知) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("未知");
    }

    @Test
    void rejectsUnknownLabelInsideExistsSubquery() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE EXISTS { MATCH (m:Entity) WHERE m:Forbidden } RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void decodesUnicodeEscapedTypeValues() {
        CypherOntologyChecker.CheckResult accepted = check(
            "MATCH (n:Entity) WHERE n.type = '\\u0050erson' RETURN n");
        CypherOntologyChecker.CheckResult rejected = check(
            "MATCH (n:Entity) WHERE n.type = '\\u0045mployee' RETURN n");

        assertThat(accepted.ok()).isTrue();
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.unknownTypeValues()).containsExactly("Employee");
    }

    private static CypherOntologyChecker.CheckResult check(String cypher) {
        return CypherOntologyChecker.check(cypher, Map.of(), ALLOWLIST, NEO4J_PROFILE);
    }
}
