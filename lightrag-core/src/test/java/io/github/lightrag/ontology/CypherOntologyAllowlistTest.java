package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CypherOntologyAllowlistTest {

    @Test
    void alwaysAllowsFallbackType() {
        CypherOntologyAllowlist allowlist = CypherOntologyAllowlist.empty();

        assertThat(allowlist.typeValues()).containsExactly("other");
        assertThat(allowlist.containsTypeValue("Other")).isTrue();
        assertThat(allowlist.containsTypeValue("other")).isTrue();
        assertThat(allowlist.containsTypeValue("Person")).isFalse();
    }

    @Test
    void normalizesLabelsToIdentityForm() {
        CypherOntologyAllowlist allowlist =
            CypherOntologyAllowlist.of(List.of("Person", " Organization "), List.of("Works At"));

        assertThat(allowlist.containsTypeValue("person")).isTrue();
        assertThat(allowlist.containsTypeValue(" PERSON ")).isTrue();
        assertThat(allowlist.containsTypeValue("unknown")).isFalse();
        assertThat(allowlist.containsRelationKeyword("works at")).isTrue();
        assertThat(allowlist.containsRelationKeyword("WORKs  At")).isTrue();
        assertThat(allowlist.containsRelationKeyword("employs")).isFalse();
    }

    @Test
    void dropsNullAndBlankLabels() {
        CypherOntologyAllowlist allowlist =
            CypherOntologyAllowlist.of(Arrays.asList("Person", null, "   "), null);

        assertThat(allowlist.typeValues()).containsExactlyInAnyOrder("person", "other");
        assertThat(allowlist.relationKeywords()).isEmpty();
    }

    @Test
    void treatsNullCollectionsAsEmpty() {
        CypherOntologyAllowlist allowlist = new CypherOntologyAllowlist(null, null);

        assertThat(allowlist.typeValues()).containsExactly("other");
        assertThat(allowlist.relationKeywords()).isEmpty();
    }

    @Test
    void containsRejectsNullAndBlankRawValues() {
        CypherOntologyAllowlist allowlist = CypherOntologyAllowlist.of(Set.of("Person"), Set.of("employs"));

        assertThat(allowlist.containsTypeValue(null)).isFalse();
        assertThat(allowlist.containsTypeValue("   ")).isFalse();
        assertThat(allowlist.containsRelationKeyword(null)).isFalse();
        assertThat(allowlist.containsRelationKeyword("  ")).isFalse();
    }
}
