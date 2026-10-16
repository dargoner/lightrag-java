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

    @Test
    void rejectsWriteKeywordAfterEscapedBacktickClose() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (`n\\u0060:Entity) CREATE (:Entity) //`\nRETURN 1");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void verifiesUnicodeEscapedTypePropertyKeyForms() {
        CypherOntologyChecker.CheckResult access = check(
            "MATCH (n:Entity) WHERE n.`\\u0074ype` = 'Employee' RETURN n");
        CypherOntologyChecker.CheckResult mapKey = check(
            "MATCH (n:Entity {`\\u0074ype`: 'Employee'}) RETURN n");
        CypherOntologyChecker.CheckResult accepted = check(
            "MATCH (n:Entity) WHERE n.`\\u0074ype` = 'Person' RETURN n");

        assertThat(access.ok()).isFalse();
        assertThat(access.unknownTypeValues()).containsExactly("Employee");
        assertThat(mapKey.ok()).isFalse();
        assertThat(mapKey.unknownTypeValues()).containsExactly("Employee");
        assertThat(accepted.ok()).isTrue();
    }

    @Test
    void rejectsUnicodeEscapeInCodePosition() {
        CypherOntologyChecker.CheckResult splitKeyword = check(
            "MATCH (n:Entity) CREA\\u0041TE (:Entity) RETURN n");
        CypherOntologyChecker.CheckResult plainAccess = check(
            "MATCH (n:Entity) WHERE n.\\u0074ype = 'Employee' RETURN n");

        assertThat(splitKeyword.ok()).isFalse();
        assertThat(plainAccess.ok()).isFalse();
        assertThat(plainAccess.unverified()).contains(
            "unicode escape outside a string literal or quoted identifier is not"
                + " statically verifiable");
    }

    @Test
    void rejectsUnknownTypeValueInSpacedMapKey() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity {match : 1, type: 'Employee'}) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsSpacedAndNestedWrapperCallConditions() {
        CypherOntologyChecker.CheckResult spacedCall = check(
            "MATCH (n:Entity) WHERE substring (n.type, 0, 2) = 'Em' RETURN n");
        CypherOntologyChecker.CheckResult nestedCall = check(
            "MATCH (n:Entity) WHERE toLower(coalesce(n.type, 'Employee')) = 'employee' RETURN n");

        assertThat(spacedCall.ok()).isFalse();
        assertThat(spacedCall.unverified()).contains(
            "type condition is not statically verifiable: unsupported function 'substring'");
        assertThat(nestedCall.ok()).isFalse();
        assertThat(nestedCall.unverified()).contains(
            "type condition is not statically verifiable: unsupported function 'coalesce'");
    }

    @Test
    void rejectsCompoundTypeValueOnAccessSide() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type + '' = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound expression involving"
                + " the type property");
    }

    @Test
    void rejectsPipeConcatenationOnTypeOperands() {
        CypherOntologyChecker.CheckResult literal = check(
            "MATCH (n:Entity) WHERE n.type = 'Person' || 'nel' RETURN n");
        CypherOntologyChecker.CheckResult inList = check(
            "MATCH (n:Entity) WHERE n.type IN ['Person'] || ['Employee'] RETURN n");
        CypherOntologyChecker.CheckResult reversed = check(
            "MATCH (n:Entity) WHERE 'Person' || 'nel' = n.type RETURN n");

        assertThat(literal.ok()).isFalse();
        assertThat(literal.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " right-hand side");
        assertThat(inList.ok()).isFalse();
        assertThat(inList.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the IN list");
        assertThat(reversed.ok()).isFalse();
        assertThat(reversed.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " left-hand side");
    }

    @Test
    void rejectsCompoundParameterListAndReversedOperandExpressions() {
        CypherOntologyChecker.CheckResult inParameter = CypherOntologyChecker.check(
            "MATCH (n:Entity) WHERE n.type IN $types + ['Employee'] RETURN n",
            Map.of("types", List.of("Person")),
            ALLOWLIST,
            NEO4J_PROFILE
        );
        CypherOntologyChecker.CheckResult reversed = check(
            "MATCH (n:Entity) WHERE 'Person' + 'nel' = n.type RETURN n");

        assertThat(inParameter.ok()).isFalse();
        assertThat(inParameter.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the IN list");
        assertThat(reversed.ok()).isFalse();
        assertThat(reversed.unverified()).contains(
            "type condition is not statically verifiable: compound expression on the"
                + " left-hand side");
    }

    @Test
    void rejectsEscapedBacktickLabel() {
        CypherOntologyChecker.CheckResult result = check("MATCH (n:`Entity``CREATE`) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Entity`CREATE");
    }

    @Test
    void rejectsNodeLabelUnionAndGroupedLabelExpressions() {
        CypherOntologyChecker.CheckResult nodeUnion = check(
            "MATCH (n:Entity|Forbidden) RETURN n");
        CypherOntologyChecker.CheckResult groupedRelation = check(
            "MATCH ()-[r:(RELATION|FORBIDDEN)]->() RETURN r");
        CypherOntologyChecker.CheckResult negatedBranch = check(
            "MATCH ()-[r:RELATION|!FORBIDDEN]->() RETURN r");

        assertThat(nodeUnion.ok()).isFalse();
        assertThat(nodeUnion.unknownLabels()).containsExactly("Forbidden");
        assertThat(groupedRelation.ok()).isFalse();
        assertThat(groupedRelation.unknownRelTypes()).containsExactly("FORBIDDEN");
        assertThat(negatedBranch.ok()).isFalse();
        assertThat(negatedBranch.unknownRelTypes()).containsExactly("FORBIDDEN");
    }

    @Test
    void rejectsUnknownLabelInShorthandExistsPattern() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity)\nWHERE EXISTS { (n)-->(m) WHERE m:Forbidden }\nRETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void keepsPlainMapProjectionsAndFunctionArgumentsValid() {
        CypherOntologyChecker.CheckResult listProjection = check(
            "MATCH (n:Entity) RETURN [{type: 'Employee'}] AS rows");
        CypherOntologyChecker.CheckResult callArgument = check(
            "MATCH (n:Entity) RETURN coalesce({type: 'Employee'}, n.type) AS t");
        CypherOntologyChecker.CheckResult anonymousPattern = check(
            "MATCH ({type: 'Person'}) RETURN 1");

        assertThat(listProjection.ok()).isTrue();
        assertThat(listProjection.unknownTypeValues()).isEmpty();
        assertThat(callArgument.ok()).isTrue();
        assertThat(anonymousPattern.ok()).isTrue();
    }

    @Test
    void rejectsAnonymousPatternWithUnknownTypeValue() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH ({type: 'Employee'}) RETURN 1");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsInsertWriteClause() {
        CypherOntologyChecker.CheckResult result = CypherOntologyChecker.check(
            "INSERT (n:Entity {workspaceId: $workspaceId, type: 'Person'}) RETURN n",
            Map.of("workspaceId", "ws-1"),
            ALLOWLIST,
            NEO4J_PROFILE
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: INSERT");
    }

    @Test
    void rejectsTypeValueAfterUnicodeEscapedBacktickVariable() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE `n\\u0060.type = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void rejectsUnicodeEscapedBacktickLabel() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:`Forbidden\\u0060) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void rejectsWriteKeywordAfterCarriageReturnComment() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) //x\rCREATE (:Entity)\nRETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void rejectsWriteKeywordAfterEscapedLineTerminatorInComment() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) //\\u000a CREATE (:Entity)\nRETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void rejectsWriteKeywordAfterEscapedBackslashInString() {
        CypherOntologyChecker.CheckResult result = check(
            "WITH 'x\\u005c\\' AS x CREATE (:Entity) //'\nRETURN x");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void rejectsWriteKeywordAfterEscapedBlockCommentEnd() {
        CypherOntologyChecker.CheckResult slashEscape = check(
            "MATCH (n:Entity) /* x *\\u002f CREATE (:Entity) RETURN n");
        CypherOntologyChecker.CheckResult starEscape = check(
            "MATCH (n:Entity) /* x \\u002a/ CREATE (:Entity) RETURN n");

        assertThat(slashEscape.ok()).isFalse();
        assertThat(slashEscape.unverified()).contains("write or unsupported keyword: CREATE");
        assertThat(starEscape.ok()).isFalse();
        assertThat(starEscape.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void verifiesTypeMapsInComplexPatternHeaders() {
        CypherOntologyChecker.CheckResult backtick = check(
            "MATCH (`n-x`:Entity {type: 'Employee'}) RETURN 1");
        CypherOntologyChecker.CheckResult union = check(
            "MATCH (n:Entity|Entity {type: 'Employee'}) RETURN n");
        CypherOntologyChecker.CheckResult grouped = check(
            "MATCH (n:(Entity) {type: 'Employee'}) RETURN n");

        assertThat(backtick.ok()).isFalse();
        assertThat(backtick.unknownTypeValues()).containsExactly("Employee");
        assertThat(union.ok()).isFalse();
        assertThat(union.unknownTypeValues()).containsExactly("Employee");
        assertThat(grouped.ok()).isFalse();
        assertThat(grouped.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsGroupedLabelExpressionTails() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:(Entity)|Forbidden) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void rejectsNegatedLabelGroups() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity|!(Entity|Forbidden)) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void rejectsAmpersandLabelExpressions() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity&Forbidden) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
    }

    @Test
    void rejectsUnionLabelPredicateOutsidePattern() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n:Entity|Forbidden RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Forbidden");
        assertThat(result.warnings()).contains(
            "label ':Forbidden' appears outside a node or relationship pattern");
    }

    @Test
    void rejectsDynamicLabels() {
        CypherOntologyChecker.CheckResult node = check(
            "MATCH (n:$('Forbidden')) RETURN n");
        CypherOntologyChecker.CheckResult relationship = check(
            "MATCH ()-[r:$('FORBIDDEN')]->() RETURN r");

        assertThat(node.ok()).isFalse();
        assertThat(node.unverified()).contains("dynamic label is not statically verifiable");
        assertThat(relationship.ok()).isFalse();
        assertThat(relationship.unverified())
            .contains("dynamic label is not statically verifiable");
    }

    @Test
    void rejectsCompoundAfterWrappedCall() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE coalesce(n.type, '') + '' = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound expression involving"
                + " the type property");
    }

    @Test
    void rejectsCompoundBeforeTypeAccess() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE 'Per' + n.type = 'Person' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound expression involving"
                + " the type property");
    }

    @Test
    void treatsCypherWhitespaceAsWhitespace() {
        CypherOntologyChecker.CheckResult rejected = check(
            "MATCH (n:Entity) WHERE n.type\u202F=\u202F'Employee' RETURN n");
        CypherOntologyChecker.CheckResult accepted = check(
            "MATCH (n:Entity) WHERE n.type\u202F=\u202F'Person' RETURN n");
        CypherOntologyChecker.CheckResult mapKey = check(
            "MATCH (n:Entity {match\u202F: 1, type: 'Employee'}) RETURN n");
        CypherOntologyChecker.CheckResult spacedAccess = check(
            "MATCH (n:Entity) WHERE n.\u202Ftype = 'Employee' RETURN n");

        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.unknownTypeValues()).containsExactly("Employee");
        assertThat(accepted.ok()).isTrue();
        assertThat(mapKey.ok()).isFalse();
        assertThat(mapKey.unknownTypeValues()).containsExactly("Employee");
        assertThat(spacedAccess.ok()).isFalse();
        assertThat(spacedAccess.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsGroupedBaseAccesses() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE (n).type = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void rejectsCompoundSubscripts() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n['ty' + 'pe'] = 'Employee' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unverified()).contains(
            "type condition is not statically verifiable: compound subscript expression");
    }

    @Test
    void keepsCompoundProjectionsValid() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) RETURN n.type + '' AS t");

        assertThat(result.ok()).isTrue();
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void keepsParenWrappedMapsAndCallArgumentsValid() {
        CypherOntologyChecker.CheckResult mapProjection = check(
            "MATCH (n:Entity) RETURN ({type: 'Employee'}) AS data");
        CypherOntologyChecker.CheckResult callArgument = check(
            "MATCH (n:Entity) RETURN count({type: 'Employee'})");

        assertThat(mapProjection.ok()).isTrue();
        assertThat(mapProjection.unknownTypeValues()).isEmpty();
        assertThat(callArgument.ok()).isTrue();
        assertThat(callArgument.unknownTypeValues()).isEmpty();
    }

    @Test
    void rejectsWriteKeywordAfterNonLfCommentSeparator() {
        CypherOntologyChecker.CheckResult escaped = check(
            "WITH 1 AS x //\\u2028 RETURN '\n"
                + "CREATE (:Entity {workspaceId: $workspaceId})\n"
                + "RETURN x //'");
        CypherOntologyChecker.CheckResult real = check(
            "WITH 1 AS x //\u2028 RETURN '\n"
                + "CREATE (:Entity {workspaceId: $workspaceId})\n"
                + "RETURN x //'");

        assertThat(escaped.ok()).isFalse();
        assertThat(escaped.unverified()).contains("write or unsupported keyword: CREATE");
        assertThat(real.ok()).isFalse();
        assertThat(real.unverified()).contains("write or unsupported keyword: CREATE");
    }

    @Test
    void rejectsEscapedBackslashEscapeChainInTypeValue() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:Entity) WHERE n.type = 'Perso\\u005c\\u006e' RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownTypeValues()).containsExactly("Perso\n");
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    void rejectsUnicodeBacktickPairedWithRawBacktickInLabel() {
        CypherOntologyChecker.CheckResult result = check(
            "MATCH (n:`Entity\\u0060`Forbidden`) RETURN n");

        assertThat(result.ok()).isFalse();
        assertThat(result.unknownLabels()).containsExactly("Entity`Forbidden");
    }

    @Test
    void rejectsCompoundComparisonAfterCallArgumentChain() {
        CypherOntologyChecker.CheckResult inline = check(
            "MATCH (n:Entity) WHERE substring(n.type + '', 0, 8) = 'Employee' RETURN n");
        CypherOntologyChecker.CheckResult nested = check(
            "MATCH (n:Entity) WHERE coalesce(substring(n.type, 0, 8), '') = 'Employee' RETURN n");

        assertThat(inline.ok()).isFalse();
        assertThat(inline.unverified()).contains(
            "type condition is not statically verifiable: compound expression involving"
                + " the type property");
        assertThat(nested.ok()).isFalse();
        assertThat(nested.unverified()).contains(
            "type condition is not statically verifiable: unsupported function 'substring'");
    }

    @Test
    void keepsPlainStringListLiteralsValid() {
        CypherOntologyChecker.CheckResult projection = check(
            "MATCH (n:Entity) RETURN ['Person', 'Organization'] AS types");
        CypherOntologyChecker.CheckResult inList = check(
            "MATCH (n:Entity) WHERE n.type IN ['Person', 'Organization'] RETURN n");

        assertThat(projection.ok()).isTrue();
        assertThat(projection.unverified()).isEmpty();
        assertThat(inList.ok()).isTrue();
        assertThat(inList.unverified()).isEmpty();
    }

    @Test
    void rejectsParenthesizedCompoundSubscript() {
        CypherOntologyChecker.CheckResult compound = check(
            "MATCH (n:Entity) WHERE n[('ty' + 'pe')] = 'Employee' RETURN n");
        CypherOntologyChecker.CheckResult plainIndex = check(
            "MATCH (n:Entity) RETURN nodes[0] AS first");

        assertThat(compound.ok()).isFalse();
        assertThat(compound.unverified()).contains(
            "type condition is not statically verifiable: compound subscript expression");
        assertThat(plainIndex.ok()).isTrue();
        assertThat(plainIndex.unverified()).isEmpty();
    }

    @Test
    void rejectsNegatedAndGroupedLabelExpressionTails() {
        CypherOntologyChecker.CheckResult doubleNegation = check(
            "MATCH (n:Entity|!!Forbidden) RETURN n");
        CypherOntologyChecker.CheckResult groupedDynamic = check(
            "MATCH (n:($('Forbidden'))) RETURN n");
        CypherOntologyChecker.CheckResult percentGroup = check(
            "MATCH (n:(%|Forbidden)) RETURN n");
        CypherOntologyChecker.CheckResult percentHeader = check(
            "MATCH (n:% {type: 'Employee'}) RETURN n");

        assertThat(doubleNegation.ok()).isFalse();
        assertThat(doubleNegation.unknownLabels()).containsExactly("Forbidden");
        assertThat(groupedDynamic.ok()).isFalse();
        assertThat(groupedDynamic.unverified())
            .contains("dynamic label is not statically verifiable");
        assertThat(percentGroup.ok()).isFalse();
        assertThat(percentGroup.unknownLabels()).containsExactly("Forbidden");
        assertThat(percentHeader.ok()).isFalse();
        assertThat(percentHeader.unknownTypeValues()).containsExactly("Employee");
    }

    @Test
    void identifiesProjectionBoundariesAndNestedDataMaps() {
        CypherOntologyChecker.CheckResult projection = check(
            "WITH n.type + '' AS t WHERE true = true RETURN t");
        CypherOntologyChecker.CheckResult nestedData = check(
            "MATCH (n:Entity) RETURN (({type: 'Employee'})) AS data");

        assertThat(projection.ok()).isTrue();
        assertThat(projection.unverified()).isEmpty();
        assertThat(nestedData.ok()).isTrue();
        assertThat(nestedData.unknownTypeValues()).isEmpty();
    }

    private static CypherOntologyChecker.CheckResult check(String cypher) {
        return CypherOntologyChecker.check(cypher, Map.of(), ALLOWLIST, NEO4J_PROFILE);
    }
}
