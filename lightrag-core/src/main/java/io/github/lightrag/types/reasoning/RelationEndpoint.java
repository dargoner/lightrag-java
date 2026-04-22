package io.github.lightrag.types.reasoning;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.Objects;

public record RelationEndpoint(String srcId, String tgtId) {
    public RelationEndpoint {
        var canonical = RelationCanonicalizer.canonicalize(
            Objects.requireNonNull(srcId, "srcId"),
            Objects.requireNonNull(tgtId, "tgtId")
        );
        srcId = canonical.srcId();
        tgtId = canonical.tgtId();
    }
}
