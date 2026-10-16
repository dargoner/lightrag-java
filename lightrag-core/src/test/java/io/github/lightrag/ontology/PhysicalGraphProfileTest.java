package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhysicalGraphProfileTest {

    @Test
    void keepsDeclaredSetsAndPropertyName() {
        PhysicalGraphProfile profile =
            new PhysicalGraphProfile(Set.of("Entity"), Set.of("RELATION"), "type");

        assertThat(profile.nodeLabels()).containsExactly("Entity");
        assertThat(profile.relationshipTypes()).containsExactly("RELATION");
        assertThat(profile.typePropertyName()).isEqualTo("type");
    }

    @Test
    void treatsNullLabelSetsAsEmpty() {
        PhysicalGraphProfile profile = new PhysicalGraphProfile(null, null, "type");

        assertThat(profile.nodeLabels()).isEmpty();
        assertThat(profile.relationshipTypes()).isEmpty();
    }

    @Test
    void stripsTypePropertyName() {
        PhysicalGraphProfile profile = new PhysicalGraphProfile(Set.of(), Set.of(), " type ");

        assertThat(profile.typePropertyName()).isEqualTo("type");
    }

    @Test
    void rejectsNullTypePropertyName() {
        assertThatThrownBy(() -> new PhysicalGraphProfile(Set.of(), Set.of(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankTypePropertyName() {
        assertThatThrownBy(() -> new PhysicalGraphProfile(Set.of(), Set.of(), "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typePropertyName must not be blank");
    }
}
