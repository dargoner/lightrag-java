package io.github.lightrag.api;

import io.github.lightrag.indexing.RelationCanonicalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationRequestContractTest {
    @Test
    void createRelationRequestRejectsBlankKeywordsAndLongEndpoints() {
        assertThatThrownBy(() -> new CreateRelationRequest("Alice", "Bob", "   ", "desc", 1.0d, "", ""))
            .hasMessageContaining("keywords must not be blank");

        var longName = "A".repeat(257);
        assertThatThrownBy(() -> new CreateRelationRequest(longName, "Bob", "works", "desc", 1.0d, "", ""))
            .hasMessageContaining("sourceEntityName must be at most 256 characters");
    }

    @Test
    void canonicalizerSortsEndpointsAndBuildsStableRelationId() {
        var canonical = RelationCanonicalizer.canonicalize("bob", "Alice");
        assertThat(canonical.srcId()).isEqualTo("Alice");
        assertThat(canonical.tgtId()).isEqualTo("bob");
        assertThat(canonical.relationId()).startsWith("rel-");
        assertThat(canonical.relationId().length()).isLessThanOrEqualTo(64);
    }
}
