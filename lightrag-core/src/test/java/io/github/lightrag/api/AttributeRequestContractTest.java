package io.github.lightrag.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributeRequestContractTest {

    @Test
    void createEntityRequestDefaultsNullAttributesToEmptyMap() {
        var request = new CreateEntityRequest("Alice", "Person", "desc", List.of());
        assertThat(request.attributes()).isEmpty();
    }

    @Test
    void requestKeysAreIdentityNormalized() {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("Age", 1L);

        var request = new CreateEntityRequest("Alice", "Person", "desc", List.of(), attributes);
        assertThat(request.attributes()).containsExactly(Map.entry("age", 1L));
    }

    @Test
    void equalValuesThatCollapseOnIdentityAreDeduplicated() {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("Age", 1L);
        attributes.put(" age ", 1L);

        var request = new CreateEntityRequest("Alice", "Person", "desc", List.of(), attributes);
        assertThat(request.attributes()).containsExactly(Map.entry("age", 1L));
    }

    @Test
    void conflictingValuesThatCollapseOnIdentityAreRejected() {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("Age", 1L);
        attributes.put(" age ", 2L);

        assertThatThrownBy(
            () -> new CreateEntityRequest("Alice", "Person", "desc", List.of(), attributes)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicting attribute values");
    }

    @Test
    void nullAttributeValuesAreRejected() {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("age", null);

        assertThatThrownBy(
            () -> new CreateEntityRequest("Alice", "Person", "desc", List.of(), attributes)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    void attributeBagLimitIsEnforced() {
        var attributes = new LinkedHashMap<String, Object>();
        for (int index = 0; index <= 64; index++) {
            attributes.put("key" + index, index);
        }

        assertThatThrownBy(
            () -> new CreateEntityRequest("Alice", "Person", "desc", List.of(), attributes)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("per-record limits");
    }

    @Test
    void editEntityRequestDistinguishesNullFromEmpty() {
        assertThat(new EditEntityRequest("Alice", null, null, null, null, null).attributes()).isNull();
        assertThat(new EditEntityRequest("Alice", null, null, null, null, Map.of()).attributes()).isEmpty();
    }

    @Test
    void editEntityRequestNormalizesProvidedAttributes() {
        var request = new EditEntityRequest(
            "Alice", null, null, null, null, Map.of(" Occupation ", "Engineer")
        );
        assertThat(request.attributes()).containsExactly(Map.entry("occupation", "Engineer"));
    }

    @Test
    void updateRelationRequestDistinguishesNullFromEmpty() {
        var keep = new UpdateRelationRequest("Alice", "Bob", "knows", null, null, null, null, null);
        assertThat(keep.attributes()).isNull();

        var clear = new UpdateRelationRequest("Alice", "Bob", "knows", null, null, null, null, Map.of());
        assertThat(clear.attributes()).isEmpty();
    }

    @Test
    void updateRelationRequestAcceptsAttributesAsTheOnlyMutableField() {
        var request = new UpdateRelationRequest(
            "Alice", "Bob", null, null, null, null, null, Map.of("since", "2024-01-01")
        );
        assertThat(request.attributes()).containsExactly(Map.entry("since", "2024-01-01"));
    }

    @Test
    void mergeEntitiesRequestDistinguishesNullFromEmpty() {
        var keep = new MergeEntitiesRequest(List.of("Bob"), "Alice", null, null, null, null);
        assertThat(keep.targetAttributes()).isNull();

        var replace = new MergeEntitiesRequest(List.of("Bob"), "Alice", null, null, null, Map.of());
        assertThat(replace.targetAttributes()).isEmpty();
    }
}
